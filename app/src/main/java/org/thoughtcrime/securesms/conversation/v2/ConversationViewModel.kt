package org.thoughtcrime.securesms.conversation.v2

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.squareup.phrase.Phrase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import network.loki.messenger.libsession_util.util.BlindedContact
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.open_groups.GroupMemberRole
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.ExpirationUtil
import org.session.libsession.utilities.StringSubstitutionConstants.DATE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.isCommunityInbox
import org.session.libsession.utilities.isGroupV2
import org.session.libsession.utilities.isStandard
import org.session.libsession.utilities.recipients.MessageType
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.recipients.displayName
import org.session.libsession.utilities.recipients.effectiveNotifyType
import org.session.libsession.utilities.recipients.getType
import org.session.libsession.utilities.recipients.repeatedWithEffectiveNotifyTypeChange
import org.session.libsession.utilities.toGroupString
import org.session.libsession.utilities.upsertContact
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.InputbarViewModel
import org.thoughtcrime.securesms.audio.AudioSlidePlayer
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.BlindMappingRepository
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.LokiAPIDatabase
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.RecipientSettingsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.GroupThreadStatus
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.groups.ExpiredGroupManager
import org.thoughtcrime.securesms.mms.AudioSlide
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.ui.components.ConversationAppBarData
import org.thoughtcrime.securesms.ui.components.ConversationAppBarPagerData
import org.thoughtcrime.securesms.ui.getSubbedString
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.DateUtils.Companion.toEpochSeconds
import org.thoughtcrime.securesms.util.UserProfileModalCommands
import org.thoughtcrime.securesms.util.UserProfileModalData
import org.thoughtcrime.securesms.util.UserProfileUtils
import org.thoughtcrime.securesms.util.castAwayType
import org.thoughtcrime.securesms.util.mapStateFlow
import org.thoughtcrime.securesms.util.mapToStateFlow
import org.thoughtcrime.securesms.webrtc.CallManager
import org.thoughtcrime.securesms.webrtc.data.State
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.EnumSet
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ConversationViewModel.Factory::class)
class ConversationViewModel @AssistedInject constructor(
    @Assisted val address: Address.Conversable,
    private val application: Application,
    private val repository: ConversationRepository,
    private val storage: StorageProtocol,
    private val groupDb: GroupDatabase,
    private val threadDb: ThreadDatabase,
    private val reactionDb: ReactionDatabase,
    private val lokiMessageDb: LokiMessageDatabase,
    private val lokiAPIDb: LokiAPIDatabase,
    private val configFactory: ConfigFactory,
    private val groupManagerV2: GroupManagerV2,
    private val callManager: CallManager,
    val legacyGroupDeprecationManager: LegacyGroupDeprecationManager,
    val dateUtils: DateUtils,
    expiredGroupManager: ExpiredGroupManager,
    private val avatarUtils: AvatarUtils,
    private val proStatusManager: ProStatusManager,
    val recipientRepository: RecipientRepository,
    recipientSettingsDatabase: RecipientSettingsDatabase,
    attachmentDatabase: AttachmentDatabase,
    private val blindMappingRepository: BlindMappingRepository,
    private val upmFactory: UserProfileUtils.UserProfileUtilsFactory,
    attachmentDownloadHandlerFactory: AttachmentDownloadHandler.Factory,
) : InputbarViewModel(
    application = application,
    proStatusManager = proStatusManager
) {
    private val edKeyPair by lazy {
        storage.getUserED25519KeyPair()
    }

    private val _uiEvents = MutableSharedFlow<ConversationUiEvent>(extraBufferCapacity = 1)
    val uiEvents: SharedFlow<ConversationUiEvent> get() = _uiEvents

    private val _dialogsState = MutableStateFlow(DialogsState())
    val dialogsState: StateFlow<DialogsState> = _dialogsState

    val threadIdFlow: StateFlow<Long?> =
        threadDb.getThreadIdIfExistsFor(address).takeIf { it != -1L }
            ?.let { MutableStateFlow(it) }
            ?: threadDb
                .updateNotifications
                .map {
                    withContext(Dispatchers.Default) {
                        threadDb.getThreadIdIfExistsFor(address)
                    }
                }
                .filter { it != -1L }
                .take(1)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = null
                )

    /**
     * Current thread ID, or -1 if it doesn't exist yet.
     *
     */
    @Deprecated("Use threadIdFlow instead")
    val threadId: Long get() = threadIdFlow.value ?: -1L

    val recipientFlow: StateFlow<Recipient> = recipientRepository.observeRecipient(address)
        .filterNotNull()
        .mapToStateFlow(viewModelScope, recipientRepository.getRecipientSync(address)) { it }

    // A flow that emits when we need to reload the conversation.
    // We normally don't need to do this but as we are transitioning to using more flow based approach,
    // the conversation is still using a cursor loader, so we need an alternative way to trigger a reload
    // than the traditional Uri change.
    @Suppress("OPT_IN_USAGE")
    val conversationReloadNotification: SharedFlow<*> = merge(
        threadIdFlow
            .filterNotNull()
            .flatMapLatest { id ->  threadDb.updateNotifications.filter { it == id } },
        recipientSettingsDatabase.changeNotification.filter { it == address },
        attachmentDatabase.changesNotification,
        reactionDb.changeNotification,
    ).debounce(200L) // debounce to avoid too many reloads
        .shareIn(viewModelScope, SharingStarted.Eagerly)


    val showSendAfterApprovalText: Flow<Boolean> get() = recipientFlow.map { r ->
        (r.acceptsBlindedCommunityMessageRequests || r.isStandardRecipient) && !r.isLocalNumber && !r.approvedMe
    }

    val openGroupFlow: StateFlow<OpenGroupApi.RoomInfo?> = recipientFlow
        .map { (it.data as? RecipientData.Community)?.roomInfo }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val openGroup: OpenGroupApi.RoomInfo?
        get() = openGroupFlow.value

    val isAdmin: StateFlow<Boolean> = recipientFlow.mapStateFlow(viewModelScope) {
        it.currentUserRole in EnumSet.of(GroupMemberRole.ADMIN, GroupMemberRole.HIDDEN_ADMIN)
    }

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

    private var userProfileModalJob: Job? = null
    private var userProfileModalUtils: UserProfileUtils? = null

    val recipient: Recipient
        get() = recipientFlow.value


    /**
     * The admin who invites us to this group(v2) conversation.
     *
     * null if this convo is not a group(v2) conversation, or error getting the info
     */
    val invitingAdmin: Recipient?
        get() {
            if (!recipient.isGroupV2Recipient) return null

            return repository.getInvitingAdmin(threadId)?.let(recipientRepository::getRecipientSync)
        }

    val groupV2ThreadState: Flow<GroupThreadStatus> get() = when {
        !address.isGroupV2 -> flowOf( GroupThreadStatus.None)
        else -> configFactory.userConfigsChanged(setOf(UserConfigType.USER_GROUPS), debounceMills = 500)
            .castAwayType()
            .onStart { emit(Unit) }
            .map {
                configFactory.getGroup(AccountId(address.toString())).let { group ->
                    when {
                        group?.destroyed == true -> GroupThreadStatus.Destroyed
                        group?.kicked == true -> GroupThreadStatus.Kicked
                        else -> GroupThreadStatus.None
                    }
                }
            }
    }

    val serverCapabilities: List<String>
        get() = when (address) {
            is Address.Community -> address.serverUrl
            is Address.CommunityBlindedId -> address.serverUrl
            else -> null
        } ?.let { storage.getServerCapabilities(it) } ?: listOf()

    val blindedPublicKey: String?
        get() = if (recipient.data !is RecipientData.Community || edKeyPair == null || !serverCapabilities.contains(OpenGroupApi.Capability.BLIND.name.lowercase())) null else {
            BlindKeyAPI.blind15KeyPairOrNull(
                ed25519SecretKey = edKeyPair!!.secretKey.data,
                serverPubKey = Hex.fromStringCondensed((recipient.data as RecipientData.Community).serverPubKey),
            )?.pubKey?.data
                ?.let { AccountId(IdPrefix.BLINDED, it) }?.hexString
        }

    val isMessageRequestThread : Boolean
        get() {
            return !recipient.isLocalNumber && !recipient.isLegacyGroupRecipient && !recipient.isCommunityRecipient && !recipient.approved
        }

    private val _showLoader = MutableStateFlow(false)
    val showLoader: StateFlow<Boolean> get() = _showLoader

    // The only criterial to trigger a exit of this conversation screen is that this conversation
    // is removed from the conversation list. So first we need to wait until this convo is added
    // to the convo list (it could have been in there already or not), then we wait until it is removed.
    // This sequence is crucial as exiting the convo screen is very abrupt and we want to avoid it
    // as much as possible.
    val shouldExit: Flow<*> get() = flow {
        // Wait until this convo is in the list
        repository.conversationListAddressesFlow.first { it.contains(address) }

        // then wait until it is removed
        repository.conversationListAddressesFlow.first { !it.contains(address) }
        emit(Unit)
    }

    private val _acceptingMessageRequest = MutableStateFlow<Boolean>(false)
    val messageRequestState: StateFlow<MessageRequestUiState> = combine(
        recipientFlow,
        _acceptingMessageRequest,
    ) { r, accepting ->
        if (accepting) MessageRequestUiState.Pending
        else buildMessageRequestState(r)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MessageRequestUiState.Invisible)


    private val _uiMessages = MutableStateFlow<List<UiMessage>>(emptyList())
    val uiMessages: StateFlow<List<UiMessage>> get() = _uiMessages

    init {
        viewModelScope.launch {
            combine(
                recipientFlow,
                legacyGroupDeprecationManager.deprecationState,
                ::getInputBarState
            ).collectLatest {
                _inputBarState.value = it
            }
        }

        // If we are able to unblind a user, we will navigate to that convo instead
        (address as? Address.CommunityBlindedId)?.let { address ->
            viewModelScope.launch {
                blindMappingRepository.observeMapping(address.serverUrl, address.blindedId)
                    .filterNotNull()
                    .collect { contactId ->
                        _uiEvents.emit(ConversationUiEvent.NavigateToConversation(contactId))
                    }
            }
        }
    }

    /**
     * returns true for outgoing message request, whether they are for 1 on 1 conversations or community outgoing MR
     */
    val isOutgoingMessageRequest: Boolean
        get() {
            return (recipient.is1on1 || recipient.isCommunityInboxRecipient) && !recipient.approvedMe
        }

    val showOptionsMenu: Boolean
        get() = !isMessageRequestThread && !isDeprecatedLegacyGroup && !isOutgoingMessageRequest

    private val isDeprecatedLegacyGroup: Boolean
        get() = recipient.isLegacyGroupRecipient && legacyGroupDeprecationManager.isDeprecated

    val canReactToMessages: Boolean
        // allow reactions if the open group is null (normal conversations) or the open group's capabilities include reactions
        get() = (openGroup == null || OpenGroupApi.Capability.REACTIONS.name.lowercase() in serverCapabilities)
                && !isDeprecatedLegacyGroup

    val canRemoveReaction: Boolean
        get() = canReactToMessages

    val legacyGroupBanner: StateFlow<CharSequence?> = combine(
        legacyGroupDeprecationManager.deprecationState,
        legacyGroupDeprecationManager.deprecatedTime,
        isAdmin
    ) { state, time, admin ->
        when {
            recipient.isLegacyGroupRecipient != true -> null
            state == LegacyGroupDeprecationManager.DeprecationState.DEPRECATED -> {
                Phrase.from(application, if (admin) R.string.legacyGroupAfterDeprecationAdmin else R.string.legacyGroupAfterDeprecationMember)
                    .format()
            }
            state == LegacyGroupDeprecationManager.DeprecationState.DEPRECATING ->
                Phrase.from(application, if (admin) R.string.legacyGroupBeforeDeprecationAdmin else R.string.legacyGroupBeforeDeprecationMember)
                .put(DATE_KEY,
                    time.withZoneSameInstant(ZoneId.systemDefault())
                        .format(dateUtils.getMediumDateTimeFormatter())
                )
                .format()

            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val showRecreateGroupButton: StateFlow<Boolean> =
        combine(isAdmin, legacyGroupDeprecationManager.deprecationState, recipientFlow) { admin, state, r ->
            admin && r.isLegacyGroupRecipient && state != LegacyGroupDeprecationManager.DeprecationState.NOT_DEPRECATING
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val showExpiredGroupBanner: Flow<Boolean> = if (!address.isGroupV2) {
        flowOf(false)
    } else {
        val groupId = AccountId(address.address)
        expiredGroupManager.expiredGroups.map { groupId in it }
    }

    private val attachmentDownloadHandler = attachmentDownloadHandlerFactory.create(viewModelScope)

    val callBanner: StateFlow<String?> = callManager.currentConnectionStateFlow.map {
        // a call is in progress if it isn't idle nor disconnected and the recipient is the person on the call
        if(it !is State.Idle && it !is State.Disconnected && callManager.recipient == recipient.address){
            // call is started, we need to differentiate between in progress vs incoming
            if(it is State.Connected) application.getString(R.string.callsInProgress)
            else application.getString(R.string.callsIncomingUnknown)
        } else null // null when the call isn't in progress / incoming
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val lastSeenMessageId: Flow<MessageId?>
        get() = threadIdFlow.flatMapLatest { id ->
            repository.getLastSentMessageID(id ?: return@flatMapLatest flowOf(null))
        }

    private fun getInputBarState(
        recipient: Recipient,
        deprecationState: LegacyGroupDeprecationManager.DeprecationState
    ): InputBarState {
        val currentCharLimitState = _inputBarState.value.charLimitState
        return when {
            // prioritise cases that demand the input to be hidden
            !shouldShowInput(recipient, deprecationState) -> InputBarState(
                contentState = InputBarContentState.Hidden,
                enableAttachMediaControls = false,
                charLimitState = currentCharLimitState
            )

            // next are cases where the  input is visible but disabled
            // when the recipient is blocked
            recipient.blocked -> InputBarState(
                contentState = InputBarContentState.Disabled(
                    text = application.getString(R.string.blockBlockedDescription),
                    onClick = {
                        _uiEvents.tryEmit(ConversationUiEvent.ShowUnblockConfirmation)
                    }
                ),
                enableAttachMediaControls = false,
                charLimitState = currentCharLimitState
            )

            // the user does not have write access in the community
            (recipient.data as? RecipientData.Community)?.roomInfo?.write == false -> InputBarState(
                contentState = InputBarContentState.Disabled(
                    text = application.getString(R.string.permissionsWriteCommunity),
                ),
                enableAttachMediaControls = false,
                charLimitState = currentCharLimitState
            )

            // other cases the input is visible, and the buttons might be disabled based on some criteria
            else -> InputBarState(
                contentState = InputBarContentState.Visible,
                enableAttachMediaControls = shouldEnableInputMediaControls(recipient),
                charLimitState = currentCharLimitState
            )
        }
    }

    private fun getAppBarData(conversation: Recipient,
                              showSearch: Boolean): ConversationAppBarData? {
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
            val subtitleTxt = application.getSubbedString(dmTypeString,
                TIME_KEY to durationAbbreviated
            )

            // .. and apply to the subtitle.
            pagerData += ConversationAppBarPagerData(
                title = subtitleTxt,
                action = {
                    showDisappearingMessages(conversation)
                },
                icon = R.drawable.ic_clock_11,
                qaTag = application.resources.getString(R.string.AccessibilityId_disappearingMessagesDisappear)
            )
        }

        val effectiveNotifyType = recipient.effectiveNotifyType()
        if (effectiveNotifyType == NotifyType.NONE || effectiveNotifyType == NotifyType.MENTIONS) {
            pagerData += ConversationAppBarPagerData(
                title = getNotificationStatusTitle(effectiveNotifyType),
                action = {
                    showNotificationSettings()
                }
            )
        }

        if (conversation.isGroupOrCommunityRecipient && conversation.approved) {
            val title = if (conversation.address is Address.Community) {
                val userCount = (conversation.data as? RecipientData.Community)?.roomInfo?.activeUsers
                    ?: 0
                application.resources.getQuantityString(R.plurals.membersActive, userCount, userCount)
            } else {
                val userCount = if (conversation.data is RecipientData.Group) {
                    conversation.data.partial.members.size
                } else { // legacy closed groups
                    groupDb.getGroupMemberAddresses(conversation.address.toGroupString(), true).size
                }
                application.resources.getQuantityString(R.plurals.members, userCount, userCount)
            }

            pagerData += ConversationAppBarPagerData(
                title = title,
                action = {
                    // This pager title no longer actionable for legacy groups
                    if (conversation.isCommunityRecipient) showConversationSettings()
                    else if (conversation.address is Address.Group) showGroupMembers(conversation.address)
                },
            )
        }

        // calculate the main app bar data
        val avatarData = avatarUtils.getUIDataFromRecipient(conversation)
        return ConversationAppBarData(
            title = conversation.takeUnless { it.isLocalNumber }?.displayName() ?: application.getString(R.string.noteToSelf),
            pagerData = pagerData,
            showCall = conversation.showCallMenu,
            showAvatar = showOptionsMenu,
            showSearch = showSearch,
            avatarUIData = avatarData,
            // show the pro badge when a conversation/user is pro, except for communities
            showProBadge = proStatusManager.shouldShowProBadge(conversation.address) && !conversation.isLocalNumber // do not show for note to self
        ).also {
            // also preload the larger version of the avatar in case the user goes to the settings
            avatarData.elements.mapNotNull { it.remoteFile }.forEach {
                val loadSize = application.resources.getDimensionPixelSize(R.dimen.xxl_profile_picture_size)

                val request = ImageRequest.Builder(application)
                    .data(it)
                    .size(loadSize, loadSize)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .build()

                application.imageLoader.enqueue(request) // preloads image
            }
        }
    }

    private fun getNotificationStatusTitle(notifyType: NotifyType): String {
        return when (notifyType) {
            NotifyType.NONE -> application.getString(R.string.notificationsHeaderMute)
            NotifyType.MENTIONS -> application.getString(R.string.notificationsHeaderMentionsOnly)
            NotifyType.ALL -> ""
        }
    }

    /**
     * Determines if the input media controls should be enabled.
     *
     * Normally we will show the input media controls, only in these situations we hide them:
     *  1. First time we send message to a person.
     *     Since we haven't been approved by them, we can't send them any media, only text
     */
    private fun shouldEnableInputMediaControls(recipient: Recipient): Boolean {
        // disable for blocked users
        if (recipient.blocked) return false

        // Specifically allow multimedia in our note-to-self
        if (recipient.isLocalNumber) return true

        // To send multimedia content to other people:
        // - For 1-on-1 conversations they must have approved us as a contact.
        val allowedFor1on1 = recipient.is1on1 && recipient.approvedMe

        // - For groups you just have to be a member of the group. Note: `isGroupRecipient` convers both legacy and V2 groups.
        val allowedForGroup = recipient.isGroupRecipient

        // - For communities you must have write access to the community
        val allowedForCommunity = (recipient.isCommunityRecipient &&
                (recipient.data as? RecipientData.Community)?.roomInfo?.write == true)

        // - For blinded recipients you must be a contact of the recipient - without which you CAN
        // send them SMS messages - but they will not get through if the recipient does not have
        // community message requests enabled. Being a "contact recipient" implies
        // `!recipient.blocksCommunityMessageRequests` in this case.
        val allowedForBlindedCommunityRecipient = recipient.isCommunityInboxRecipient && recipient.isStandardRecipient

        // If any of the above are true we allow sending multimedia files - otherwise we don't
        return allowedFor1on1 || allowedForGroup || allowedForCommunity || allowedForBlindedCommunityRecipient
    }

    /**
     * Determines if the input bar should be shown.
     *
     * For these situations we hide the input bar:
     *  1. The user has been kicked from a group(v2), OR
     *  2. The legacy group is inactive, OR
     *  3. The legacy group is deprecated, OR
     *  4. Blinded recipient who have disabled message request from community members
     */
    private fun shouldShowInput(
        recipient: Recipient,
        deprecationState: LegacyGroupDeprecationManager.DeprecationState
    ): Boolean {
        return when {
            recipient.isGroupV2Recipient -> !repository.isGroupReadOnly(recipient)
            recipient.isLegacyGroupRecipient -> {
                groupDb.getGroup(recipient.address.toGroupString()).orNull()?.isActive == true &&
                        deprecationState != LegacyGroupDeprecationManager.DeprecationState.DEPRECATED
            }
            address.isCommunityInbox && !recipient.acceptsBlindedCommunityMessageRequests -> false
            else -> true
        }
    }

    private fun buildMessageRequestState(recipient: Recipient): MessageRequestUiState {
        if (
            // Essential: the recipient is not us
            !recipient.isSelf &&

            // Req 1: we must have an contact entry that says we haven't approved them.
            // This is needed because if we haven't added this person into the contact data,
            // this would be a request from us instead.
            (
                    (recipient.data is RecipientData.Contact && !recipient.data.approved) ||
                            (recipient.data is RecipientData.Group && !recipient.data.partial.approved)
            ) &&

            // Req 2: the type of conversation supports message request
            (recipient.address is Address.Standard || recipient.address is Address.Group)
        ) {

            return MessageRequestUiState.Visible(
                acceptButtonText = if (recipient.isGroupOrCommunityRecipient) {
                    R.string.messageRequestGroupInviteDescription
                } else {
                    R.string.messageRequestsAcceptDescription
                },
                // You can block a 1to1 conversation, or a normal groups v2 conversation
                blockButtonText = application.getString(R.string.block)
            )
        }

        return MessageRequestUiState.Invisible
    }

    override fun onCleared() {
        super.onCleared()

        // Stop all voice message when exiting this page
        AudioSlidePlayer.stopAll()
    }

    fun saveDraft(text: String) {
        GlobalScope.launch(Dispatchers.IO) {
            repository.saveDraft(threadId, text)
        }
    }

    fun getDraft(): String? {
        val draft: String? = repository.getDraft(threadId)

        viewModelScope.launch(Dispatchers.IO) {
            repository.clearDrafts(threadId)
        }

        return draft
    }

    fun block() {
        // inviting admin will be non-null if this request is a closed group message request
        val recipient = invitingAdmin ?: recipient
        if (recipient.isStandardRecipient || recipient.isGroupV2Recipient) {
            viewModelScope.launch {
                repository.setBlocked(recipient.address, true)
            }
        }

        if (recipient.isGroupV2Recipient) {
            viewModelScope.launch {
                groupManagerV2.onBlocked(AccountId(recipient.address.toString()))
            }
        }
    }

    fun unblock() {
        if (address.isStandard) {
            viewModelScope.launch {
                repository.setBlocked(address, false)
            }
        }
    }

    fun handleMessagesDeletion(messages: Set<MessageRecord>){
        viewModelScope.launch(Dispatchers.IO) {
            val allSentByCurrentUser = messages.all { it.isOutgoing }

            val conversationType = recipient.getType()

            // hashes are required if wanting to delete messages from the 'storage server'
            // They are not required for communities OR if all messages are outgoing
            // also we can only delete deleted messages and control messages (marked as deleted) locally
            val canDeleteForEveryone = messages.all{ !it.isDeleted && !it.isControlMessage } && (
                    messages.all { it.isOutgoing } ||
                    conversationType == MessageType.COMMUNITY ||
                            messages.all { lokiMessageDb.getMessageServerHash(it.messageId) != null }
                    )

            // There are three types of dialogs for deletion:
            // 1- Delete on device only OR all devices - Used for Note to self
            // 2- Delete on device only OR for everyone - Used for 'admins' or a user's own messages, as long as the message have a server hash
            // 3- Delete on device only - Used otherwise
            when {
                // the conversation is a note to self
                conversationType == MessageType.NOTE_TO_SELF -> {
                    _dialogsState.update {
                        it.copy(deleteEveryone = DeleteForEveryoneDialogData(
                                messages = messages,
                                defaultToEveryone = false,
                                everyoneEnabled = canDeleteForEveryone,
                                messageType = conversationType,
                                deleteForEveryoneLabel = application.getString(R.string.deleteMessageDevicesAll),
                                warning = if(canDeleteForEveryone) null else
                                    application.resources.getQuantityString(
                                        R.plurals.deleteMessageNoteToSelfWarning, messages.count(), messages.count()
                                    )
                            )
                        )
                    }
                }

                // If the user is an admin or is interacting with their own message And are allowed to delete for everyone
                (isAdmin.value || allSentByCurrentUser) && canDeleteForEveryone -> {
                    _dialogsState.update {
                        it.copy(
                            deleteEveryone = DeleteForEveryoneDialogData(
                                messages = messages,
                                defaultToEveryone = isAdmin.value,
                                everyoneEnabled = true,
                                deleteForEveryoneLabel = application.getString(R.string.deleteMessageEveryone),
                                messageType = conversationType
                            )
                        )
                    }
                }

                // for non admins, users interacting with someone else's message, or control messages
                else -> {
                    _dialogsState.update {
                        it.copy(
                            deleteEveryone = DeleteForEveryoneDialogData(
                                messages = messages,
                                defaultToEveryone = false,
                                everyoneEnabled = false, // disable 'delete for everyone' - can only delete locally in this case
                                messageType = conversationType,
                                deleteForEveryoneLabel = application.getString(R.string.deleteMessageEveryone),
                                warning = application.resources.getQuantityString(
                                    R.plurals.deleteMessageWarning, messages.count(), messages.count()
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * This delete the message locally only.
     * Attachments and other related data will be removed from the db.
     * If the messages were already marked as deleted they will be removed fully from the db,
     * otherwise they will appear as a special type of message
     * that says something like "This message was deleted"
     */
    fun deleteLocally(messages: Set<MessageRecord>) {
        // make sure to stop audio messages, if any
        messages.filterIsInstance<MmsMessageRecord>()
            .mapNotNull { it.slideDeck.audioSlide }
            .forEach(::stopMessageAudio)

        // if the message was already marked as deleted or control messages, remove it from the db instead
        if(messages.all { it.isDeleted || it.isControlMessage }){
            // Remove the message locally (leave nothing behind)
            repository.deleteMessages(messages = messages)
        } else {
            // only mark as deleted (message remains behind with "This message was deleted on this device" )
            repository.markAsDeletedLocally(
                messages = messages,
                displayedMessage = application.getString(R.string.deleteMessageDeletedLocally)
            )
        }

        // show confirmation toast
        Toast.makeText(
            application,
            application.resources.getQuantityString(R.plurals.deleteMessageDeleted, messages.count(), messages.count()),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * This will mark the messages as deleted, for everyone.
     * Attachments and other related data will be removed from the db,
     * but the messages themselves won't be removed from the db.
     * Instead they will appear as a special type of message
     * that says something like "This message was deleted"
     */
    private fun markAsDeletedForEveryone(
        data: DeleteForEveryoneDialogData
    ) = viewModelScope.launch {
        // make sure to stop audio messages, if any
        data.messages.filterIsInstance<MmsMessageRecord>()
            .mapNotNull { it.slideDeck.audioSlide }
            .forEach(::stopMessageAudio)

        // the exact logic for this will depend on the messages type
        when(data.messageType){
            MessageType.NOTE_TO_SELF -> markAsDeletedForEveryoneNoteToSelf(data)
            MessageType.ONE_ON_ONE -> markAsDeletedForEveryone1On1(data)
            MessageType.LEGACY_GROUP -> markAsDeletedForEveryoneLegacyGroup(data.messages)
            MessageType.GROUPS_V2 -> markAsDeletedForEveryoneGroupsV2(data)
            MessageType.COMMUNITY -> markAsDeletedForEveryoneCommunity(data)
        }
    }

    private fun markAsDeletedForEveryoneNoteToSelf(data: DeleteForEveryoneDialogData){
        viewModelScope.launch(Dispatchers.IO) {
            // show a loading indicator
            _showLoader.value = true

            // delete remotely
            try {
                repository.deleteNoteToSelfMessagesRemotely(address, data.messages)

                // When this is done we simply need to remove the message locally (leave nothing behind)
                repository.deleteMessages(messages = data.messages)

                // show confirmation toast
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        application,
                        application.resources.getQuantityString(
                            R.plurals.deleteMessageDeleted,
                            data.messages.count(),
                            data.messages.count()
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.w("Loki", "FAILED TO delete messages ${data.messages} ")
                // failed to delete - show a toast and get back on the modal
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        application,
                        application.resources.getQuantityString(
                            R.plurals.deleteMessageFailed,
                            data.messages.size,
                            data.messages.size
                        ), Toast.LENGTH_SHORT
                    ).show()
                }

                _dialogsState.update { it.copy(deleteEveryone = data) }
            }

            // hide loading indicator
            _showLoader.value = false
        }
    }

    private fun markAsDeletedForEveryone1On1(data: DeleteForEveryoneDialogData){
        viewModelScope.launch(Dispatchers.IO) {
            // show a loading indicator
            _showLoader.value = true

            // delete remotely
            try {
                repository.delete1on1MessagesRemotely(address, data.messages)

                // When this is done we simply need to remove the message locally
                repository.markAsDeletedLocally(
                    messages = data.messages,
                    displayedMessage = application.getString(R.string.deleteMessageDeletedGlobally)
                )

                // show confirmation toast
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        application,
                        application.resources.getQuantityString(
                            R.plurals.deleteMessageDeleted,
                            data.messages.count(),
                            data.messages.count()
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.w("Loki", "FAILED TO delete messages ${data.messages} ")
                // failed to delete - show a toast and get back on the modal
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        application,
                        application.resources.getQuantityString(
                            R.plurals.deleteMessageFailed,
                            data.messages.size,
                            data.messages.size
                        ), Toast.LENGTH_SHORT
                    ).show()
                }

                _dialogsState.update { it.copy(deleteEveryone = data) }
            }

            // hide loading indicator
            _showLoader.value = false
        }
    }

    private fun markAsDeletedForEveryoneLegacyGroup(messages: Set<MessageRecord>){
        viewModelScope.launch(Dispatchers.IO) {
            // delete remotely
            try {
                repository.deleteLegacyGroupMessagesRemotely(address, messages)

                // When this is done we simply need to remove the message locally
                repository.markAsDeletedLocally(
                    messages = messages,
                    displayedMessage = application.getString(R.string.deleteMessageDeletedGlobally)
                )

                // show confirmation toast
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        application,
                        application.resources.getQuantityString(
                            R.plurals.deleteMessageDeleted,
                            messages.count(),
                            messages.count()
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.w("Loki", "FAILED TO delete messages ${messages} ")
                // failed to delete - show a toast and get back on the modal
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        application,
                        application.resources.getQuantityString(
                            R.plurals.deleteMessageFailed,
                            messages.size,
                            messages.size
                        ), Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun markAsDeletedForEveryoneGroupsV2(data: DeleteForEveryoneDialogData){
        viewModelScope.launch(Dispatchers.Default) {
            // show a loading indicator
            _showLoader.value = true

            try {
                repository.deleteGroupV2MessagesRemotely(address, data.messages)

                // the repo will handle the internal logic (calling `/delete` on the swarm
                // and sending 'GroupUpdateDeleteMemberContentMessage'
                // When this is done we simply need to remove the message locally
                repository.markAsDeletedLocally(
                    messages = data.messages,
                    displayedMessage = application.getString(R.string.deleteMessageDeletedGlobally)
                )

                // show confirmation toast
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        application,
                        application.resources.getQuantityString(
                            R.plurals.deleteMessageDeleted,
                            data.messages.count(), data.messages.count()
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("Loki", "FAILED TO delete messages ${data.messages}", e)
                // failed to delete - show a toast and get back on the modal
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        application,
                        application.resources.getQuantityString(
                            R.plurals.deleteMessageFailed,
                            data.messages.size,
                            data.messages.size
                        ), Toast.LENGTH_SHORT
                    ).show()
                }

                _dialogsState.update { it.copy(deleteEveryone = data) }
            }

            // hide loading indicator
            _showLoader.value = false
        }
    }

    private fun markAsDeletedForEveryoneCommunity(data: DeleteForEveryoneDialogData){
        viewModelScope.launch(Dispatchers.IO) {
            // show a loading indicator
            _showLoader.value = true

            // delete remotely
            try {
                repository.deleteCommunityMessagesRemotely(address as Address.Community, data.messages)

                // When this is done we simply need to remove the message locally (leave nothing behind)
                repository.deleteMessages(messages = data.messages)

                // show confirmation toast
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        application,
                        application.resources.getQuantityString(
                            R.plurals.deleteMessageDeleted,
                            data.messages.count(),
                            data.messages.count()
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.w("Loki", "FAILED TO delete messages ${data.messages} ")
                // failed to delete - show a toast and get back on the modal
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        application,
                        application.resources.getQuantityString(
                            R.plurals.deleteMessageFailed,
                            data.messages.size,
                            data.messages.size
                        ), Toast.LENGTH_SHORT
                    ).show()
                }

                _dialogsState.update { it.copy(deleteEveryone = data) }
            }

            // hide loading indicator
            _showLoader.value = false
        }
    }

    /**
     * Stops audio player if its current playing is the one given in the message.
     */
    private fun stopMessageAudio(audioSlide: AudioSlide) {
        AudioSlidePlayer.getInstance()?.takeIf { it.audioSlide == audioSlide }?.stop()
    }

    fun banUser(recipient: Address) = viewModelScope.launch {
        repository.banUser(
            community = address as Address.Community,
            userId = (recipient as Address.WithAccountId).accountId
        )
            .onSuccess {
                showMessage(application.getString(R.string.banUserBanned))
            }
            .onFailure {
                showMessage(application.getString(R.string.banErrorFailed))
            }
    }

    fun banAndDeleteAll(messageRecord: MessageRecord) = viewModelScope.launch {
        repository.banAndDeleteAll(
            community = address as Address.Community,
            userId = (messageRecord.individualRecipient.address as Address.WithAccountId).accountId)
            .onSuccess {
                // At this point the server side messages have been successfully deleted..
                showMessage(application.getString(R.string.banUserBanned))

                // ..so we can now delete all their messages in this thread from local storage & remove the views.
                repository.deleteAllLocalMessagesInThreadFromSenderOfMessage(messageRecord)
            }
            .onFailure {
                showMessage(application.getString(R.string.banErrorFailed))
            }
    }

    fun acceptMessageRequest(): Job = viewModelScope.launch {
        val currentState = messageRequestState.value as? MessageRequestUiState.Visible
            ?: return@launch Log.w("Loki", "Current state was not visible for accept message request action")

        _acceptingMessageRequest.value = true

        repository.acceptMessageRequest(address)
            .onFailure {
                Log.w("Loki", "Couldn't accept message request due to error", it)
                _acceptingMessageRequest.value = false
            }
    }

    fun declineMessageRequest() = viewModelScope.launch {
        repository.declineMessageRequest(address)
            .onFailure {
                Log.w("Loki", "Couldn't decline message request due to error", it)
            }
    }

    private fun showMessage(message: String) {
        _uiMessages.update { messages ->
            messages + UiMessage(
                id = UUID.randomUUID().mostSignificantBits,
                message = message
            )
        }
    }

    fun messageShown(messageId: Long) {
        _uiMessages.update { messages ->
            messages.filterNot { it.id == messageId }
        }
    }

    fun legacyBannerRecipient(context: Context): Recipient? = recipient.run {
        storage.getLastLegacyRecipient(address.toString())?.let { recipientRepository.getRecipientSync(fromSerialized(it)) }
    }

    fun downloadPendingAttachment(attachment: DatabaseAttachment) {
        attachmentDownloadHandler.downloadPendingAttachment(attachment)
    }

    fun retryFailedAttachments(attachments: List<DatabaseAttachment>){
        attachmentDownloadHandler.retryFailedAttachments(attachments)
    }

    /**
     * Implicitly approve the recipient.
     *
     * @return The (kotlin coroutine) job of sending job message request, if one should be sent. The job
     * instance is normally just for observing purpose. Note that the completion of this job
     * does not mean the message is sent, it only means the the successful submission to the message
     * send queue and they will be sent later. You will not be able to observe the completion
     * of message sending through this method.
     */
    fun implicitlyApproveRecipient(): Job? {
        if (messageRequestState.value is MessageRequestUiState.Visible) {
            return acceptMessageRequest()
        }

        return null
    }

    fun onCommand(command: Commands) {
        when (command) {
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

                deleteLocally(command.messages)
            }
            is Commands.MarkAsDeletedForEveryone -> {
                markAsDeletedForEveryone(command.data)
            }


            is Commands.ClearEmoji -> {
                clearEmoji(command.emoji, command.messageId)
            }

            Commands.RecreateGroup -> {
                _dialogsState.update {
                    it.copy(recreateGroupConfirm = true)
                }
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

            is Commands.NavigateToConversation -> {
                _uiEvents.tryEmit(ConversationUiEvent.NavigateToConversation(command.address))
            }

            is Commands.HideUserProfileModal -> {
                _dialogsState.update { it.copy(userProfileModal = null) }
            }

            is Commands.HandleUserProfileCommand -> {
                userProfileModalUtils?.onCommand(command.upmCommand)
            }
        }
    }

    private fun clearEmoji(emoji: String, messageId: MessageId){
        viewModelScope.launch(Dispatchers.Default) {
            reactionDb.deleteEmojiReactions(emoji, messageId)
            (address as? Address.Community)?.let { openGroup ->
                lokiMessageDb.getServerID(messageId)?.let { serverId ->
                    OpenGroupApi.deleteAllReactions(
                        openGroup.room,
                        openGroup.serverUrl,
                        serverId,
                        emoji
                    )
                }
            }
        }
    }

    fun onEmojiClear(emoji: String, messageId: MessageId) {
        // show a confirmation dialog
        _dialogsState.update {
            it.copy(clearAllEmoji = ClearAllEmoji(emoji, messageId))
        }
    }

    fun onSearchOpened() {
        _searchOpened.value = true
    }

    fun onSearchClosed(){
        _searchOpened.value = false
    }

    private fun showDisappearingMessages(recipient: Recipient) {
        recipient.let { convo ->
            if (convo.isLegacyGroupRecipient) {
                groupDb.getGroup(convo.address.toGroupString()).orNull()?.run {
                    if (!isActive) return
                }
            }

            _uiEvents.tryEmit(ConversationUiEvent.ShowDisappearingMessages(convo.address))
        }
    }

    private fun showGroupMembers(address: Address.Group) {
        _uiEvents.tryEmit(ConversationUiEvent.ShowGroupMembers(address))
    }

    private fun showConversationSettings() {
        _uiEvents.tryEmit(ConversationUiEvent.ShowConversationSettings(
            threadAddress = address
        ))
    }

    private fun showNotificationSettings() {
        _uiEvents.tryEmit(ConversationUiEvent.ShowNotificationSettings(address))
    }

    fun showUserProfileModal(userAddress: Address) {
        // get the helper class for the selected user
        userProfileModalUtils = upmFactory.create(
            userAddress = userAddress,
            threadAddress = address,
            scope = viewModelScope
        )

        // cancel previous job if any then listen in on the changes
        userProfileModalJob?.cancel()
        userProfileModalJob = viewModelScope.launch {
            userProfileModalUtils?.userProfileModalData?.collect { upmData ->
                _dialogsState.update { it.copy(userProfileModal = upmData) }
            }
        }
    }

    fun beforeSendMessage() {
        when (address) {
            is Address.Standard -> {
                if (recipient.isSelf) {
                    // Do nothing
                    return
                }

                val existingContact = configFactory.withUserConfigs { it.contacts.get(address.accountId.hexString) }

                if (existingContact?.approved != true || existingContact.priority == PRIORITY_HIDDEN) {
                    configFactory.withMutableUserConfigs { configs ->
                        configs.contacts.upsertContact(address) {
                            // Mark the existing contact as approve if we haven't approved them before
                            if (existingContact?.approved != true) {
                                approved = true
                                name = name.takeIf { it.isNotBlank() } ?: recipient.displayName(attachesBlindedId = false)
                                profilePicture = profilePicture.takeIf { it != UserPic.DEFAULT }
                                    ?: recipient.avatar?.toUserPic() ?: UserPic.DEFAULT
                                priority = PRIORITY_VISIBLE
                                profileUpdatedEpochSeconds = recipient.data.profileUpdatedAt?.toEpochSeconds() ?: profileUpdatedEpochSeconds
                                createdEpochSeconds = createdEpochSeconds.takeIf { it > 0L } ?: Instant.now().toEpochSeconds()
                            }

                            // If the contact was hidden, make it visible now
                            if (priority == PRIORITY_HIDDEN) {
                                priority = PRIORITY_VISIBLE
                            }
                        }
                    }
                }
            }

            is Address.CommunityBlindedId -> {
                if (configFactory.withUserConfigs { it.contacts.getBlinded(address.blindedId.address) } == null) {
                    configFactory.withMutableUserConfigs { configs ->
                        val serverPubKey = configs.userGroups.allCommunityInfo()
                            .first { it.community.baseUrl == address.serverUrl }
                            .community
                            .pubKeyHex

                        configs.contacts.setBlinded(
                            BlindedContact(
                                id = address.blindedId.blindedId.hexString,
                                communityServer = address.serverUrl,
                                communityServerPubKeyHex = serverPubKey,
                                name = recipient.displayName(attachesBlindedId = false),
                                createdEpochSeconds = ZonedDateTime.now().toEpochSecond(),
                                profilePic = recipient.data.avatar?.toUserPic() ?: UserPic.DEFAULT,
                                profileUpdatedEpochSeconds = recipient.data.profileUpdatedAt?.toEpochSeconds() ?: 0L,
                                priority = PRIORITY_VISIBLE
                            )
                        )
                    }
                }
            }

            is Address.Community,
            is Address.Group,
            is Address.LegacyGroup -> {
                // No need to create config entries as they should be created prior to
                // being able to send messages in these conversations.
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(address: Address.Conversable): ConversationViewModel
    }

    data class DialogsState(
        val openLinkDialogUrl: String? = null,
        val clearAllEmoji: ClearAllEmoji? = null,
        val deleteEveryone: DeleteForEveryoneDialogData? = null,
        val recreateGroupConfirm: Boolean = false,
        val recreateGroupData: RecreateGroupDialogData? = null,
        val userProfileModal: UserProfileModalData? = null,
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

    sealed interface Commands {
        data class ShowOpenUrlDialog(val url: String?) : Commands

        data class ClearEmoji(val emoji:String, val messageId: MessageId) : Commands

        data object HideDeleteEveryoneDialog : Commands
        data object HideClearEmoji : Commands

        data class MarkAsDeletedLocally(val messages: Set<MessageRecord>): Commands
        data class MarkAsDeletedForEveryone(val data: DeleteForEveryoneDialogData): Commands

        data object RecreateGroup : Commands
        data object ConfirmRecreateGroup : Commands
        data object HideRecreateGroupConfirm : Commands
        data object HideRecreateGroup : Commands
        data class NavigateToConversation(val address: Address.Conversable) : Commands

        data object HideUserProfileModal: Commands
        data class HandleUserProfileCommand(
            val upmCommand: UserProfileModalCommands
        ): Commands
    }
}

data class UiMessage(val id: Long, val message: String)


sealed interface ConversationUiEvent {
    data class NavigateToConversation(val address: Address.Conversable) : ConversationUiEvent
    data class ShowDisappearingMessages(val address: Address) : ConversationUiEvent
    data class ShowNotificationSettings(val address: Address) : ConversationUiEvent
    data class ShowGroupMembers(val groupAddress: Address.Group) : ConversationUiEvent
    data class ShowConversationSettings(val threadAddress: Address.Conversable) : ConversationUiEvent
    data object ShowUnblockConfirmation : ConversationUiEvent
}

sealed interface MessageRequestUiState {
    data object Invisible : MessageRequestUiState

    data object Pending : MessageRequestUiState

    data class Visible(
        @param:StringRes val acceptButtonText: Int,
        // If null, the block button shall not be shown
        val blockButtonText: String? = null
    ) : MessageRequestUiState
}
