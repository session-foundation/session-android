package org.thoughtcrime.securesms.conversation.v3

import android.content.Context
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentState
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ExpirationUtil
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY
import org.session.libsession.utilities.recipients.MessageType
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.recipients.displayName
import org.session.libsession.utilities.recipients.effectiveNotifyType
import org.session.libsession.utilities.recipients.repeatedWithEffectiveNotifyTypeChange
import org.session.libsession.utilities.toGroupString
import org.thoughtcrime.securesms.InputbarViewModel
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.RecipientSettingsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.ui.SimpleDialogData
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.ui.components.ConversationAppBarData
import org.thoughtcrime.securesms.ui.components.ConversationAppBarPagerData
import org.thoughtcrime.securesms.ui.getSubbedString
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.UserProfileModalCommands
import org.thoughtcrime.securesms.util.UserProfileModalData
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
    private val proStatusManager: ProStatusManager,
    ) : InputbarViewModel(
    context = context,
    proStatusManager = proStatusManager,
    recipientRepository = recipientRepository,
) {
    //todo convov3 remove references to threadId once we have the notification refactor
    val threadIdFlow: StateFlow<Long?> = merge(
        // Initial lookup off main thread
        flow { emit(withContext(Dispatchers.IO) { storage.getThreadId(address) }) },
        // Also listen for thread creation in case it doesn't exist yet
        threadDb.updateNotifications
            .map { withContext(Dispatchers.IO) { storage.getThreadId(address) } }
    )
        .filterNotNull()
        .take(1)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _uiState: MutableStateFlow<UIState> = MutableStateFlow(
        UIState()
    )
    val uiState: StateFlow<UIState> = _uiState

    private val _dialogsState = MutableStateFlow(DialogsState())
    val dialogsState: StateFlow<DialogsState> = _dialogsState

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

    // obtain the last seen message id
    private val lastSeen: StateFlow<Long?> = threadIdFlow
        .filterNotNull()
        .flatMapLatest { id ->
            flow {
                emit(withContext(Dispatchers.IO) {
                    threadDb.getLastSeenAndHasSent(id).first().takeIf { it > 0 }
                })
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversationItems: Flow<PagingData<ConversationDataMapper.ConversationItem>> = combine(
        threadIdFlow.filterNotNull(),
        lastSeen,
    ) { id, lastSeen ->
        Pair(id, lastSeen)
    }
        .flatMapLatest { (id, lastSeen) ->
            Pager(
                config = PagingConfig(pageSize = 50, initialLoadSize = 100, enablePlaceholders = false),
                pagingSourceFactory = {
                    ConversationPagingSource(
                        threadId = id,
                        mmsSmsDatabase = mmsSmsDatabase,
                        reverse = true,
                        dataMapper = dataMapper,
                        threadRecipient = recipient,
                        localUserAddress = storage.getUserPublicKey() ?: "",
                        lastSentMessageId = mmsSmsDatabase.getLastSentMessageID(id),
                        lastSeen = lastSeen,
                    ).also { pagingSource = it }
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

            is Commands.ShowOpenUrlDialog -> {
                _dialogsState.update {
                    it.copy(openLinkDialogUrl = command.url)
                }
            }

            is Commands.HideDeleteEveryoneDialog -> {
                _dialogsState.update {
                    it.copy(deleteEveryone = null)
                }
            }

            is Commands.HideClearEmoji -> {
                _dialogsState.update {
                    it.copy(clearAllEmoji = null)
                }
            }

            is Commands.MarkAsDeletedLocally -> {
                // hide dialog first
                _dialogsState.update {
                    it.copy(deleteEveryone = null)
                }

                //todo convov3 implement 'deleteLocally'
                //deleteLocally(command.messages)
            }
            is Commands.MarkAsDeletedForEveryone -> {
                //todo convov3 implement
            }

            is Commands.ClearEmoji -> {
                //todo convov3 implement
            }

            Commands.HideRecreateGroupConfirm -> {
                _dialogsState.update {
                    it.copy(recreateGroupConfirm = false)
                }
            }

            Commands.ConfirmRecreateGroup -> {
                _dialogsState.update {
                    it.copy(
                        recreateGroupConfirm = false,
                        recreateGroupData = recipient.address.toString().let { addr -> RecreateGroupDialogData(legacyGroupId = addr) }
                    )
                }
            }

            Commands.HideRecreateGroup -> {
                _dialogsState.update {
                    it.copy(recreateGroupData = null)
                }
            }

            is Commands.HideUserProfileModal -> {
                _dialogsState.update { it.copy(userProfileModal = null) }
            }

            is Commands.HandleUserProfileCommand -> {
                //todo convov3 implement
                //userProfileModalUtils?.onCommand(command.upmCommand)
            }

            is Commands.JoinCommunity -> {
                //todo convov3 implement
                //joinCommunity(command.url)
            }

            is Commands.HideJoinCommunityDialog -> {
                _dialogsState.update {
                    it.copy(
                        joinCommunity = null
                    )
                }
            }

            is Commands.DownloadAttachments -> {
                viewModelScope.launch {
                    val databaseAttachment = command.attachment

                    storage.setAutoDownloadAttachments(recipient.address, true)

                    val attachmentId = databaseAttachment.attachmentId.rowId
                    if (databaseAttachment.transferState == AttachmentState.PENDING.value
                        && storage.getAttachmentUploadJob(attachmentId) == null
                    ) {
                        //todo convov3 implement

                        // start download
                        /*jobQueue.get().add(
                            attachmentDownloadJobFactory.create(
                                attachmentId,
                                databaseAttachment.mmsId
                            )
                        )*/
                    }
                }
            }

            is Commands.HideAttachmentDownloadDialog -> {
                _dialogsState.update {
                    it.copy(
                        attachmentDownload = null
                    )
                }
            }

            Commands.HideSimpleDialog -> {
                _dialogsState.update {
                    it.copy(showSimpleDialog = null)
                }
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

        // Dialogs
        data class ShowOpenUrlDialog(val url: String?) : Commands
        data class ClearEmoji(val emoji:String, val messageId: MessageId) : Commands
        data object HideDeleteEveryoneDialog : Commands
        data object HideClearEmoji : Commands
        data class MarkAsDeletedLocally(val messages: Set<MessageRecord>): Commands
        data class MarkAsDeletedForEveryone(val data: DeleteForEveryoneDialogData): Commands

        data class JoinCommunity(val url: String): Commands
        data object HideJoinCommunityDialog: Commands

        data class DownloadAttachments(val attachment: DatabaseAttachment): Commands
        data object HideAttachmentDownloadDialog: Commands

        data object HideSimpleDialog : Commands

        data object ConfirmRecreateGroup : Commands
        data object HideRecreateGroupConfirm : Commands
        data object HideRecreateGroup : Commands

        data object HideUserProfileModal: Commands
        data class HandleUserProfileCommand(
            val upmCommand: UserProfileModalCommands
        ): Commands
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

    // Dialogs
    data class DialogsState(
        val showSimpleDialog: SimpleDialogData? = null,
        val openLinkDialogUrl: String? = null,
        val clearAllEmoji: ClearAllEmoji? = null,
        val deleteEveryone: DeleteForEveryoneDialogData? = null,
        val recreateGroupConfirm: Boolean = false,
        val recreateGroupData: RecreateGroupDialogData? = null,
        val userProfileModal: UserProfileModalData? = null,
        val joinCommunity: JoinCommunityDialogData? = null,
        val attachmentDownload: ConfirmAttachmentDownloadDialogData? = null
    )

    data class JoinCommunityDialogData(
        val communityName: String,
        val communityUrl: String
    )

    data class ConfirmAttachmentDownloadDialogData(
        val attachment: DatabaseAttachment,
        val conversationName: String
    )

    data class RecreateGroupDialogData(
        val legacyGroupId: String,
    )

    data class DeleteForEveryoneDialogData(
        val messages: Set<MessageRecord>,
        val messageType: MessageType,
        val defaultToEveryone: Boolean,
        val everyoneEnabled: Boolean,
        val deleteForEveryoneLabel: String,
        val warning: String? = null
    )

    data class ClearAllEmoji(
        val emoji: String,
        val messageId: MessageId
    )
}
