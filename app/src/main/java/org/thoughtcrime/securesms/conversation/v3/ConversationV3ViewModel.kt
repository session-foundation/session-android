package org.thoughtcrime.securesms.conversation.v3

import android.content.Context
import android.widget.Toast
import androidx.navigation.NavOptionsBuilder
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
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
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
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ExpirationUtil
import org.session.libsession.utilities.OpenGroupUrlParser
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.recipients.displayName
import org.session.libsession.utilities.recipients.effectiveNotifyType
import org.session.libsession.utilities.recipients.repeatedWithEffectiveNotifyTypeChange
import org.session.libsession.utilities.toGroupString
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.InputbarViewModel
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.RecipientSettingsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.getLastSeen
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.groups.OpenGroupManager
import org.thoughtcrime.securesms.links.LinkChecker
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.ui.UINavigator
import org.thoughtcrime.securesms.ui.components.ConversationAppBarData
import org.thoughtcrime.securesms.ui.components.ConversationAppBarPagerData
import org.thoughtcrime.securesms.ui.getSubbedString
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.castAwayType
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
    private val openGroupManager: OpenGroupManager,
    private val linkChecker: LinkChecker,
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
        threadDb.changeNotification
            .map { withContext(Dispatchers.IO) { storage.getThreadId(address) } }
    )
        .filterNotNull()
        .take(1)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)


    private val unreadCount: StateFlow<Int> = threadIdFlow
        .filterNotNull()
        .map { id -> withContext(Dispatchers.IO) { mmsSmsDatabase.getUnreadCount(id) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _uiState: MutableStateFlow<UIState> = MutableStateFlow(
        UIState()
    )
    val uiState: StateFlow<UIState> = _uiState

    private val _dialogsState = MutableStateFlow(ConversationDialogsState())
    val dialogsState: StateFlow<ConversationDialogsState> = _dialogsState

    private val _scrollEvent = Channel<ScrollEvent>(Channel.CONFLATED)
    val scrollEvent: Flow<ScrollEvent> = _scrollEvent.receiveAsFlow()

    private var scrollState: ConversationScrollState = ConversationScrollState(
        isNearBottom = true,
        isFullyScrolled = true,
        firstVisibleIndex = 0,
        lastVisibleIndex = 0,
        totalItemCount = 0,
    )

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
    private val lastSeen: StateFlow<Long?> = threadDb.changeNotification
        .filter { it.address == address }
        .castAwayType()
        .onStart { emit(Unit) }
        .mapNotNull { threadDb.getLastSeen(address)?.toEpochMilliseconds() }
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
            .flatMapLatest { id -> threadDb.changeNotification.filter { it.id == id } },
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

        // listen to changes to the unread count
        viewModelScope.launch {
            unreadCount.collect { updateScrollToBottomButton() }
        }
    }

    private fun updateScrollToBottomButton() {
        val count = unreadCount.value
        val label: String? = when {
            scrollState.isNearBottom -> null
            count <= 0 -> ""
            count < 10_000 -> count.toString()
            else -> "9999+"
        }
        _uiState.update { it.copy(scrollToBottomButton = label) }
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
                    navigateTo(ConversationV3Destination.RouteNotifications(address))
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
                    if (conversation.isCommunityRecipient) {
                        navigateTo(ConversationV3Destination.RouteConversationSettings(address))
                    }
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

            navigateTo(ConversationV3Destination.RouteDisappearingMessages(address))
        }
    }

    private fun handleLink(url: String) {
        viewModelScope.launch {
            _dialogsState.update {
                it.copy(
                    urlDialog = linkChecker.check(url),
                )
            }
        }
    }

    private fun openOrJoinCommunity(url: String) {
        val openGroup = try {
            OpenGroupUrlParser.parseUrl(url)
        } catch (_: OpenGroupUrlParser.Error) {
            Toast.makeText(context, R.string.communityEnterUrlErrorInvalidDescription, Toast.LENGTH_SHORT)
                .show()
            return
        }

        _dialogsState.update { it.copy(urlDialog = null) }

        viewModelScope.launch {
            try {
                openGroupManager.add(
                    server = openGroup.server,
                    room = openGroup.room,
                    publicKey = openGroup.serverPublicKey,
                )

                // after joining or if already joined, open the conversation
                val communityAddress = Address.Community(openGroup.server, openGroup.room)
                navigateTo(
                    destination = ConversationV3Destination.RouteConversation(communityAddress),
                ) {
                    //todo convov3 confirm that we want a new stack
                    popUpTo(ConversationV3Destination.RouteConversation(address)) {
                        inclusive = true
                    }
                }
            } catch (e: Exception) {
                Log.e("ConversationV3ViewModel", "Error joining community", e)
                Toast.makeText(context, R.string.communityErrorDescription, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    fun onCommand(command: ConversationCommand) {
        when (command) {
            // Navigation
            is ConversationCommand.GoTo -> {
                navigateTo(command.destination)
            }

            // Conversation screen
            is ConversationCommand.OnScrollStateChanged -> {
                if (command.scrollState != scrollState) {
                    scrollState = command.scrollState
                    updateScrollToBottomButton()
                }
            }

            ConversationCommand.ScrollToBottom -> {
                scrollState = scrollState.copy(isNearBottom = true, isFullyScrolled = true)
                _uiState.update { it.copy(scrollToBottomButton = null) }
                _scrollEvent.trySend(ScrollEvent.ToBottom)
            }

            is ConversationCommand.ScrollToMessage -> {
                _scrollEvent.trySend(
                    ScrollEvent.ToMessage(
                        messageId = command.messageId,
                        smoothScroll = command.smoothScroll,
                        highlight = command.highlight,
                    )
                )
            }

            // Dialog related
            is ConversationCommand.HandleLink -> {
                handleLink(command.url)
            }

            ConversationCommand.HideUrlDialog -> {
                _dialogsState.update {
                    it.copy(urlDialog = null)
                }
            }

            ConversationCommand.HideDeleteEveryoneDialog -> {
                _dialogsState.update {
                    it.copy(deleteEveryone = null)
                }
            }

            ConversationCommand.HideClearEmoji -> {
                _dialogsState.update {
                    it.copy(clearAllEmoji = null)
                }
            }

            is ConversationCommand.MarkAsDeletedLocally -> {
                // hide dialog first
                _dialogsState.update {
                    it.copy(deleteEveryone = null)
                }

                //todo convov3 implement 'deleteLocally'
                //deleteLocally(command.messages)
            }
            is ConversationCommand.MarkAsDeletedForEveryone -> {
                //todo convov3 implement
            }

            is ConversationCommand.ClearEmoji -> {
                //todo convov3 implement
            }

            ConversationCommand.HideRecreateGroupConfirm -> {
                _dialogsState.update {
                    it.copy(recreateGroupConfirm = false)
                }
            }

            ConversationCommand.ConfirmRecreateGroup -> {
                _dialogsState.update {
                    it.copy(
                        recreateGroupConfirm = false,
                        recreateGroupData = recipient.address.toString().let { addr -> RecreateGroupDialogData(legacyGroupId = addr) }
                    )
                }
            }

            ConversationCommand.HideRecreateGroup -> {
                _dialogsState.update {
                    it.copy(recreateGroupData = null)
                }
            }

            ConversationCommand.HideUserProfileModal -> {
                _dialogsState.update { it.copy(userProfileModal = null) }
            }

            is ConversationCommand.HandleUserProfileCommand -> {
                //todo convov3 implement
                //userProfileModalUtils?.onCommand(command.upmCommand)
            }

            is ConversationCommand.OpenOrJoinCommunity -> {
                openOrJoinCommunity(command.url)
            }

            is ConversationCommand.DownloadAttachments -> {
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

            ConversationCommand.HideAttachmentDownloadDialog -> {
                _dialogsState.update {
                    it.copy(
                        attachmentDownload = null
                    )
                }
            }

            ConversationCommand.HideSimpleDialog -> {
                _dialogsState.update {
                    it.copy(showSimpleDialog = null)
                }
            }
        }
    }

    private fun navigateTo(
        destination: ConversationV3Destination,
        navOptions: NavOptionsBuilder.() -> Unit = {}
    ){
        viewModelScope.launch {
            navigator.navigate(
                destination = destination,
                navOptions = navOptions,
            )
        }
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
        /** null = hidden, "" = shown without badge, "8" / "9999+" = shown with badge */
        val scrollToBottomButton: String? = null,
    )


    /**
     * One-shot event consumed by the Composable layer.
     * Every scroll trigger in the app (notifications, search, quotes,
     * scroll-to-bottom button) flows through this single type.
     */
    sealed interface ScrollEvent {
        data object ToBottom : ScrollEvent

        data class ToMessage(
            val messageId: MessageId,
            val smoothScroll: Boolean = true,
            val highlight: Boolean = true,
        ) : ScrollEvent
    }

}
