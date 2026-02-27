package org.thoughtcrime.securesms.conversation.v3

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ExpirationUtil
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.recipients.displayName
import org.session.libsession.utilities.recipients.effectiveNotifyType
import org.session.libsession.utilities.recipients.repeatedWithEffectiveNotifyTypeChange
import org.session.libsession.utilities.toGroupString
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.RecipientSettingsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.ui.components.ConversationAppBarData
import org.thoughtcrime.securesms.ui.components.ConversationAppBarPagerData
import org.thoughtcrime.securesms.ui.getSubbedString
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.mapToStateFlow


@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ConversationV3ViewModel.Factory::class)
class ConversationV3ViewModel @AssistedInject constructor(
    @Assisted private val address: Address.Conversable,
    @Assisted private val navigator: UINavigator<ConversationV3Destination>,
    @param:ApplicationContext private val context: Context,
    private val avatarUtils: AvatarUtils,
    private val storage: StorageProtocol,
    private val recipientRepository: RecipientRepository,
    private val groupDb: GroupDatabase,
    private val legacyGroupDeprecationManager: LegacyGroupDeprecationManager,
    private val threadDb: ThreadDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val recipientSettingsDatabase: RecipientSettingsDatabase,
    private val attachmentDatabase: AttachmentDatabase,
    private val reactionDb: ReactionDatabase,
    private val dataMapper: ConversationDataMapper,
    ) : ViewModel() {

    val threadIdFlow: StateFlow<Long?> =
        storage.getThreadId(address)
            ?.let { MutableStateFlow(it) }
            ?: threadDb.updateNotifications
                .map { storage.getThreadId(address) }
                .flowOn(Dispatchers.Default)
                .filterNotNull()
                .take(1)
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _uiState: MutableStateFlow<UIState> = MutableStateFlow(
        UIState()
    )
    val uiState: StateFlow<UIState> = _uiState

    val recipientFlow: StateFlow<Recipient> = recipientRepository.observeRecipient(address)
        .filterNotNull()
        .mapToStateFlow(viewModelScope, recipientRepository.getRecipientSync(address)) { it }

    val recipient: Recipient
        get() = recipientFlow.value

    /**
     * returns true for outgoing message request, whether they are for 1 on 1 conversations or community outgoing MR
     */
    private val isOutgoingMessageRequest: Boolean
        get() {
            return (recipient.is1on1 || recipient.isCommunityInboxRecipient) && !recipient.approvedMe
        }

    private val isMessageRequestThread : Boolean
        get() {
            return !recipient.isLocalNumber && !recipient.isLegacyGroupRecipient && !recipient.isCommunityRecipient && !recipient.approved
        }

    private val isDeprecatedLegacyGroup: Boolean
        get() = recipient.isLegacyGroupRecipient && legacyGroupDeprecationManager.isDeprecated

    val showAvatar: Boolean
        get() = !isMessageRequestThread && !isDeprecatedLegacyGroup && !isOutgoingMessageRequest

    private val _searchOpened = MutableStateFlow(false)

    val appBarData: StateFlow<ConversationAppBarData> = combine(
        recipientFlow.repeatedWithEffectiveNotifyTypeChange(),
        _searchOpened,
        ::getAppBarData
    ).filterNotNull()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConversationAppBarData(
            title = "",
            pagerData = emptyList(),
            showCall = false,
            showAvatar = false,
            showSearch = false,
            avatarUIData = AvatarUIData(emptyList())
        ))

    private var pagingSource: ConversationPagingSource? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val conversationItems: Flow<PagingData<ConversationDataMapper.ConversationItem>> = threadIdFlow
        .filterNotNull()
        .flatMapLatest { id ->
            Pager(
                config = PagingConfig(
                    pageSize = 50,
                    initialLoadSize = 100,
                    enablePlaceholders = false
                ),
                pagingSourceFactory = {
                    ConversationPagingSource(
                        id,
                        mmsSmsDatabase,
                        reverse = true,
                        dataMapper = dataMapper,
                        threadRecipient = recipient,
                        localUserAddress = storage.getUserPublicKey() ?: "",
                        lastSentMessageId = mmsSmsDatabase.getLastSentMessageID(id),
                    ).also {
                        pagingSource = it
                    }
                }
            ).flow
        }
        .cachedIn(viewModelScope)

    @Suppress("OPT_IN_USAGE")
    val databaseChanges: SharedFlow<*> = merge(
        threadIdFlow
            .filterNotNull()
            .flatMapLatest { id -> threadDb.updateNotifications.filter { it == id } },
        recipientSettingsDatabase.changeNotification.filter { it == address },
        attachmentDatabase.changesNotification,
        reactionDb.changeNotification,
    ).debounce(200L) // debounce to avoid too many reloads
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 0)


    init {
        viewModelScope.launch {
            databaseChanges.collectLatest {
                // Forces the Pager to re-query the PagingSource
                pagingSource?.invalidate()
            }
        }
    }


    private fun getAppBarData(conversation: Recipient, showSearch: Boolean): ConversationAppBarData {
        // sort out the pager data, if any
        val pagerData: MutableList<ConversationAppBarPagerData> = mutableListOf()
        // Specify the disappearing messages subtitle if we should
        val expiryMode = conversation.expiryMode
        if (expiryMode.expiryMillis > 0) {
            // Get the type of disappearing message and the abbreviated duration..
            val dmTypeString = when (expiryMode) {
                is ExpiryMode.AfterRead -> R.string.disappearingMessagesDisappearAfterReadState
                else -> R.string.disappearingMessagesDisappearAfterSendState
            }
            val durationAbbreviated = ExpirationUtil.getExpirationAbbreviatedDisplayValue(expiryMode.expirySeconds)

            // ..then substitute into the string..
            val subtitleTxt = context.getSubbedString(dmTypeString,
                TIME_KEY to durationAbbreviated
            )

            // .. and apply to the subtitle.
            pagerData += ConversationAppBarPagerData(
                title = subtitleTxt,
                action = {
                    showDisappearingMessages(conversation)
                },
                icon = R.drawable.ic_clock_11,
                qaTag = context.resources.getString(R.string.AccessibilityId_disappearingMessagesDisappear)
            )
        }

        val effectiveNotifyType = conversation.effectiveNotifyType()
        if (effectiveNotifyType == NotifyType.NONE || effectiveNotifyType == NotifyType.MENTIONS) {
            pagerData += ConversationAppBarPagerData(
                title = getNotificationStatusTitle(effectiveNotifyType),
                action = {
                    navigateTo(ConversationV3Destination.RouteNotifications)
                }
            )
        }

        if (conversation.isGroupOrCommunityRecipient && conversation.approved) {
            val title = if (conversation.address is Address.Community) {
                val userCount = (conversation.data as? RecipientData.Community)?.roomInfo?.activeUsers
                    ?: 0
                context.resources.getQuantityString(R.plurals.membersActive, userCount, userCount)
            } else {
                val userCount = if (conversation.data is RecipientData.Group) {
                    conversation.data.members.size
                } else { // legacy closed groups
                    groupDb.getGroupMemberAddresses(conversation.address.toGroupString(), true).size
                }
                context.resources.getQuantityString(R.plurals.members, userCount, userCount)
            }

            pagerData += ConversationAppBarPagerData(
                title = title,
                action = {
                    // This pager title no longer actionable for legacy groups
                    if (conversation.isCommunityRecipient) navigateTo(ConversationV3Destination.RouteConversationSettings)
                    else if (conversation.address is Address.Group) navigateTo(ConversationV3Destination.RouteGroupMembers(conversation.address))
                },
            )
        }

        // calculate the main app bar data
        val avatarData = avatarUtils.getUIDataFromRecipient(conversation)
        return ConversationAppBarData(
            title = conversation.takeUnless { it.isLocalNumber }?.displayName() ?: context.getString(R.string.noteToSelf),
            pagerData = pagerData,
            showCall = conversation.showCallMenu,
            showAvatar = showAvatar,
            showSearch = showSearch,
            avatarUIData = avatarData,
            // show the pro badge when a conversation/user is pro, except for communities
            showProBadge = conversation.shouldShowProBadge && !conversation.isLocalNumber // do not show for note to self
        ).also {
            // also preload the larger version of the avatar in case the user goes to the settings
            avatarData.elements.mapNotNull { it.remoteFile }.forEach {
                val loadSize = context.resources.getDimensionPixelSize(R.dimen.xxl_profile_picture_size)

                val request = ImageRequest.Builder(context)
                    .data(it)
                    .size(loadSize, loadSize)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .build()

                context.imageLoader.enqueue(request) // preloads image
            }
        }
    }

    private fun getNotificationStatusTitle(notifyType: NotifyType): String {
        return when (notifyType) {
            NotifyType.NONE -> context.getString(R.string.notificationsHeaderMute)
            NotifyType.MENTIONS -> context.getString(R.string.notificationsHeaderMentionsOnly)
            NotifyType.ALL -> ""
        }
    }

    private fun showDisappearingMessages(recipient: Recipient) {
        recipient.let { convo ->
            if (convo.isLegacyGroupRecipient) {
                groupDb.getGroup(convo.address.toGroupString())?.run {
                    if (!isActive) return
                }
            }

            navigateTo(ConversationV3Destination.RouteDisappearingMessages)
        }
    }

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.GoTo -> {
                navigateTo(command.destination)
            }
        }
    }

    private fun navigateTo(destination: ConversationV3Destination){
        viewModelScope.launch {
            navigator.navigate(destination)
        }
    }


    sealed interface Commands {
        data class GoTo(val destination: ConversationV3Destination) : Commands
    }

    @AssistedFactory
    interface Factory {
        fun create(
            address: Address.Conversable,
            navigator: UINavigator<ConversationV3Destination>
        ): ConversationV3ViewModel
    }

    data class UIState(
        val name: String = "",
    )
}
