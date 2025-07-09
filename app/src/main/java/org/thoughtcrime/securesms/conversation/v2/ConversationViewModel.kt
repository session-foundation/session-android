package org.thoughtcrime.securesms.conversation.v2

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.squareup.phrase.Phrase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.ExpirationUtil
import org.session.libsession.utilities.StringSubstitutionConstants.DATE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.recipients.MessageType
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.getType
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.InputbarViewModel
import org.thoughtcrime.securesms.audio.AudioSlidePlayer
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.LokiAPIDatabase
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.LokiThreadDatabase
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.GroupThreadStatus
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.groups.ExpiredGroupManager
import org.thoughtcrime.securesms.groups.OpenGroupManager
import org.thoughtcrime.securesms.mms.AudioSlide
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.ui.components.ConversationAppBarData
import org.thoughtcrime.securesms.ui.components.ConversationAppBarPagerData
import org.thoughtcrime.securesms.ui.getSubbedString
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.avatarOptions
import org.thoughtcrime.securesms.util.mapStateFlow
import org.thoughtcrime.securesms.util.mapToStateFlow
import org.thoughtcrime.securesms.webrtc.CallManager
import org.thoughtcrime.securesms.webrtc.data.State
import java.time.ZoneId
import java.util.UUID

@HiltViewModel(assistedFactory = ConversationViewModel.Factory::class)
class ConversationViewModel @AssistedInject constructor(
    @Assisted val address: Address,
    private val application: Application,
    private val repository: ConversationRepository,
    private val storage: StorageProtocol,
    private val messageDataProvider: MessageDataProvider,
    private val groupDb: GroupDatabase,
    private val threadDb: ThreadDatabase,
    private val reactionDb: ReactionDatabase,
    private val lokiMessageDb: LokiMessageDatabase,
    private val lokiAPIDb: LokiAPIDatabase,
    private val textSecurePreferences: TextSecurePreferences,
    private val configFactory: ConfigFactory,
    private val groupManagerV2: GroupManagerV2,
    private val callManager: CallManager,
    val legacyGroupDeprecationManager: LegacyGroupDeprecationManager,
    val dateUtils: DateUtils,
    private val expiredGroupManager: ExpiredGroupManager,
    private val avatarUtils: AvatarUtils,
    private val openGroupManager: OpenGroupManager,
    private val proStatusManager: ProStatusManager,
    private val recipientRepository: RecipientRepository,
    private val lokiThreadDatabase: LokiThreadDatabase,
) : InputbarViewModel(
    application = application,
    proStatusManager = proStatusManager
) {

    val threadId: Long = threadDb.getThreadIdIfExistsFor(address)

    private val edKeyPair by lazy {
        storage.getUserED25519KeyPair()
    }

    val showSendAfterApprovalText: Boolean
        get() = recipient?.run {
            // if the contact is a 1on1 or a blinded 1on1 that doesn't block requests - and is not the current user - and has not yet approved us
            (getBlindedRecipient(recipient)?.acceptsCommunityMessageRequests == true || isContactRecipient) && !isLocalNumber && !approvedMe
        } ?: false

    private val _uiEvents = MutableSharedFlow<ConversationUiEvent>(extraBufferCapacity = 1)
    val uiEvents: SharedFlow<ConversationUiEvent> get() = _uiEvents

    private val _dialogsState = MutableStateFlow(DialogsState())
    val dialogsState: StateFlow<DialogsState> = _dialogsState

    val recipientFlow: StateFlow<Recipient?> = recipientRepository.observeRecipient(address)
        .filterNotNull()
        .mapToStateFlow(viewModelScope, recipientRepository.getRecipientSync(address)) { it }

    val openGroupFlow: StateFlow<OpenGroup?> =
        lokiThreadDatabase.retrieveAndObserveOpenGroup(viewModelScope, threadId) ?: MutableStateFlow(null)

    val openGroup: OpenGroup?
        get() = openGroupFlow.value

    val isAdmin: StateFlow<Boolean> = when {
        address.isCommunity -> openGroupFlow.mapStateFlow(viewModelScope) { og -> isUserCommunityManager(og) }
        address.isGroupV2 -> configFactory.userConfigsChanged(500)
            .onStart { emit(Unit) }
            .mapToStateFlow(viewModelScope, initialData = null) {
                configFactory.getGroup(AccountId(address.address))?.hasAdminKey() == true
            }

        address.isLegacyGroup -> textSecurePreferences.watchLocalNumber()
            .filterNotNull()
            .mapToStateFlow(viewModelScope, initialData = textSecurePreferences.getLocalNumber()) { myAddress ->
                myAddress != null && storage.getGroup(address.toGroupString())
                    ?.admins?.contains(fromSerialized(myAddress)) == true
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        else -> MutableStateFlow(false)
    }

    private val _searchOpened = MutableStateFlow(false)

    val appBarData: StateFlow<ConversationAppBarData> = combine(
        recipientFlow.filterNotNull(),
        openGroupFlow,
        _searchOpened,
        ::getAppBarData
    ).stateIn(viewModelScope, SharingStarted.Eagerly, ConversationAppBarData(
            title = "",
            pagerData = emptyList(),
            showCall = false,
            showAvatar = false,
            showSearch = false,
            avatarUIData = AvatarUIData(emptyList())
        ))

    val recipient: Recipient?
        get() = recipientFlow.value

    val blindedRecipient: Recipient?
        get() = recipient?.let { recipient ->
            getBlindedRecipient(recipient)
        }

    private fun getBlindedRecipient(recipient: Recipient?): Recipient? =
        when {
            recipient?.isCommunityOutboxRecipient == true -> recipient
            recipient?.isCommunityInboxRecipient == true ->
                repository.maybeGetBlindedRecipient(recipient.address)
                    ?.let(recipientRepository::getRecipientSync)
            else -> null
        }

    /**
     * The admin who invites us to this group(v2) conversation.
     *
     * null if this convo is not a group(v2) conversation, or error getting the info
     */
    val invitingAdmin: Recipient?
        get() {
            val recipient = recipient ?: return null
            if (!recipient.isGroupV2Recipient) return null

            return repository.getInvitingAdmin(threadId)?.let(recipientRepository::getRecipientSync)
        }

    val groupV2ThreadState: Flow<GroupThreadStatus> get() = when {
        !address.isGroupV2 -> flowOf( GroupThreadStatus.None)
        else -> configFactory.userConfigsChanged(500)
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
        get() = openGroup?.let { storage.getServerCapabilities(it.server) } ?: listOf()

    val blindedPublicKey: String?
        get() = if (openGroup == null || edKeyPair == null || !serverCapabilities.contains(OpenGroupApi.Capability.BLIND.name.lowercase())) null else {
            BlindKeyAPI.blind15KeyPairOrNull(
                ed25519SecretKey = edKeyPair!!.secretKey.data,
                serverPubKey = Hex.fromStringCondensed(openGroup!!.publicKey),
            )?.pubKey?.data
                ?.let { AccountId(IdPrefix.BLINDED, it) }?.hexString
        }

    val isMessageRequestThread : Boolean
        get() {
            val recipient = recipient ?: return false
            return !recipient.isLocalNumber && !recipient.isLegacyGroupRecipient && !recipient.isCommunityRecipient && !recipient.approved
        }

    private val _showLoader = MutableStateFlow(false)
    val showLoader: StateFlow<Boolean> get() = _showLoader

    val shouldExit: Flow<Boolean> get() = recipientFlow.map { it == null }

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

    private val _charLimitState = MutableStateFlow<InputBarCharLimitState?>(null)
    val charLimitState: StateFlow<InputBarCharLimitState?> get() = _charLimitState

    /**
     * returns true for outgoing message request, whether they are for 1 on 1 conversations or community outgoing MR
     */
    val isOutgoingMessageRequest: Boolean
        get() {
            val recipient = recipient ?: return false
            return (recipient.is1on1 || recipient.isCommunityInboxRecipient) && !recipient.approvedMe
        }

    val showOptionsMenu: Boolean
        get() = !isMessageRequestThread && !isDeprecatedLegacyGroup && !isOutgoingMessageRequest

    private val isDeprecatedLegacyGroup: Boolean
        get() = recipient?.isLegacyGroupRecipient == true && legacyGroupDeprecationManager.isDeprecated

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
            recipient?.isLegacyGroupRecipient != true -> null
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
        combine(isAdmin, legacyGroupDeprecationManager.deprecationState) { admin, state ->
            admin && recipient?.isLegacyGroupRecipient == true
                    && state != LegacyGroupDeprecationManager.DeprecationState.NOT_DEPRECATING
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val showExpiredGroupBanner: Flow<Boolean> = if (recipient?.isGroupV2Recipient != true) {
        flowOf(false)
    } else {
        val groupId = AccountId(recipient!!.address.toString())
        expiredGroupManager.expiredGroups.map { groupId in it }
    }

    private val attachmentDownloadHandler = AttachmentDownloadHandler(
        storage = storage,
        messageDataProvider = messageDataProvider,
        scope = viewModelScope,
        recipientRepository = recipientRepository,
    )

    val callBanner: StateFlow<String?> = callManager.currentConnectionStateFlow.map {
        // a call is in progress if it isn't idle nor disconnected and the recipient is the person on the call
        if(it !is State.Idle && it !is State.Disconnected && callManager.recipient == recipient?.address){
            // call is started, we need to differentiate between in progress vs incoming
            if(it is State.Connected) application.getString(R.string.callsInProgress)
            else application.getString(R.string.callsIncomingUnknown)
        } else null // null when the call isn't in progress / incoming
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val lastSeenMessageId: Flow<MessageId?>
        get() = repository.getLastSentMessageID(threadId)

    private fun getInputBarState(
        recipient: Recipient?,
        community: OpenGroup?,
        deprecationState: LegacyGroupDeprecationManager.DeprecationState
    ): InputBarState {
        return when {
            // prioritise cases that demand the input to be hidden
            !shouldShowInput(recipient, deprecationState) -> InputBarState(
                contentState = InputBarContentState.Hidden,
                enableAttachMediaControls = false
            )

            // next are cases where the  input is visible but disabled
            // when the recipient is blocked
            recipient?.blocked == true -> InputBarState(
                contentState = InputBarContentState.Disabled(
                    text = application.getString(R.string.blockBlockedDescription),
                    onClick = {
                        _uiEvents.tryEmit(ConversationUiEvent.ShowUnblockConfirmation)
                    }
                ),
                enableAttachMediaControls = false
            )

            // the user does not have write access in the community
            community?.canWrite == false -> InputBarState(
                contentState = InputBarContentState.Disabled(
                    text = application.getString(R.string.permissionsWriteCommunity),
                ),
                enableAttachMediaControls = false
            )

            // other cases the input is visible, and the buttons might be disabled based on some criteria
            else -> InputBarState(
                contentState = InputBarContentState.Visible,
                enableAttachMediaControls = shouldEnableInputMediaControls(recipient, community)
            )
        }
    }

    private suspend fun getAppBarData(conversation: Recipient, community: OpenGroup?, showSearch: Boolean): ConversationAppBarData {
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
                    showDisappearingMessages()
                },
                icon = R.drawable.ic_clock_11,
                qaTag = application.resources.getString(R.string.AccessibilityId_disappearingMessagesDisappear)
            )
        }

        if (conversation.isMuted() || conversation.notifyType == RecipientDatabase.NOTIFY_TYPE_MENTIONS) {
            pagerData += ConversationAppBarPagerData(
                title = getNotificationStatusTitle(conversation),
                action = {
                    showNotificationSettings()
                }
            )
        }

        if (conversation.isGroupOrCommunityRecipient && conversation.approved) {
            val title = if (conversation.isCommunityRecipient) {
                val userCount = community?.let { lokiAPIDb.getUserCount(it.room, it.server) } ?: 0
                application.resources.getQuantityString(R.plurals.membersActive, userCount, userCount)
            } else {
                val userCount = if (conversation.isGroupV2Recipient) {
                    storage.getMembers(conversation.address.toString()).size
                } else { // legacy closed groups
                    groupDb.getGroupMemberAddresses(conversation.address.toGroupString(), true).size
                }
                application.resources.getQuantityString(R.plurals.members, userCount, userCount)
            }
            pagerData += ConversationAppBarPagerData(
                title = title,
                action = {
                    showGroupMembers()
                },
            )
        }

        // calculate the main app bar data
        val avatarData = avatarUtils.getUIDataFromRecipient(conversation)
        return ConversationAppBarData(
            title = conversation.takeUnless { it.isLocalNumber }?.displayName ?: application.getString(R.string.noteToSelf),
            pagerData = pagerData,
            showCall = conversation.showCallMenu,
            showAvatar = showOptionsMenu,
            showSearch = showSearch,
            avatarUIData = avatarData
        ).also {
            // also preload the larger version of the avatar in case the user goes to the settings
            avatarData.elements.mapNotNull { it.contactPhoto }.forEach {
                val loadSize = application.resources.getDimensionPixelSize(R.dimen.large_profile_picture_size)
                Glide.with(application).load(it)
                    .avatarOptions(loadSize)
                    .preload(loadSize, loadSize)
            }
        }
    }

    private fun getNotificationStatusTitle(conversation: Recipient): String {
        return if(conversation.isMuted()) application.getString(R.string.notificationsHeaderMute)
        else application.getString(R.string.notificationsHeaderMentionsOnly)
    }

    /**
     * Determines if the input media controls should be enabled.
     *
     * Normally we will show the input media controls, only in these situations we hide them:
     *  1. First time we send message to a person.
     *     Since we haven't been approved by them, we can't send them any media, only text
     */
    private fun shouldEnableInputMediaControls(recipient: Recipient?, openGroup: OpenGroup?): Boolean {

        // Specifically disallow multimedia if we don't have a recipient to send anything to
        if (recipient == null) {
            Log.i("ConversationViewModel", "Will not enable media controls for a null recipient.")
            return false
        }

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
        val allowedForCommunity = (recipient.isCommunityRecipient && openGroup?.canWrite == true)

        // - For blinded recipients you must be a contact of the recipient - without which you CAN
        // send them SMS messages - but they will not get through if the recipient does not have
        // community message requests enabled. Being a "contact recipient" implies
        // `!recipient.blocksCommunityMessageRequests` in this case.
        val allowedForBlindedCommunityRecipient = recipient.isCommunityInboxRecipient && recipient.isContactRecipient

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
        recipient: Recipient?,
        deprecationState: LegacyGroupDeprecationManager.DeprecationState
    ): Boolean {
        return when {
            recipient?.isGroupV2Recipient == true -> !repository.isGroupReadOnly(recipient)
            recipient?.isLegacyGroupRecipient == true -> {
                groupDb.getGroup(recipient.address.toGroupString()).orNull()?.isActive == true &&
                        deprecationState != LegacyGroupDeprecationManager.DeprecationState.DEPRECATED
            }
            getBlindedRecipient(recipient)?.acceptsCommunityMessageRequests == false -> false
            else -> true
        }
    }

    private fun buildMessageRequestState(recipient: Recipient?): MessageRequestUiState {
        // The basic requirement of showing a message request is:
        // 1. The other party has not been approved by us, AND
        // 2. We haven't sent a message to them before (if we do, we would be the one requesting permission), AND
        // 3. We have received message from them AND
        // 4. The type of conversation supports message request (only 1to1 and groups v2)

        if (
            recipient != null &&

            // Req 1: we haven't approved the other party
            (!recipient.approved && !recipient.isLocalNumber) &&

            // Req 4: the type of conversation supports message request
            (recipient.is1on1 || recipient.isGroupV2Recipient) &&

            // Req 2: we haven't sent a message to them before
            !threadDb.getLastSeenAndHasSent(threadId).second() &&

            // Req 3: we have received message from them
            threadDb.getMessageCount(threadId) > 0
        ) {

            return MessageRequestUiState.Visible(
                acceptButtonText = if (recipient.isGroupOrCommunityRecipient) {
                    R.string.messageRequestGroupInviteDescription
                } else {
                    R.string.messageRequestsAcceptDescription
                },
                // You can block a 1to1 conversation, or a normal groups v2 conversation
                blockButtonText = when {
                    recipient.is1on1 ||
                            recipient.isGroupV2Recipient -> application.getString(R.string.block)
                    else -> null
                }
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
        val recipient = invitingAdmin ?: recipient ?: return Log.w("Loki", "Recipient was null for block action")
        if (recipient.isContactRecipient || recipient.isGroupV2Recipient) {
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
        val recipient = recipient ?: return Log.w("Loki", "Recipient was null for unblock action")
        if (recipient.isContactRecipient) {
            viewModelScope.launch {
                repository.setBlocked(recipient.address, false)
            }
        }
    }

    fun deleteThread() = viewModelScope.launch {
        repository.deleteThread(threadId)
    }

    fun handleMessagesDeletion(messages: Set<MessageRecord>){
        val conversation = recipient
        if (conversation == null) {
            Log.w("ConversationActivityV2", "Asked to delete messages but could not obtain viewModel recipient - aborting.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val allSentByCurrentUser = messages.all { it.isOutgoing }

            val conversationType = conversation.getType()

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
            repository.deleteMessages(messages = messages, threadId = threadId)
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
        if(recipient == null) return showMessage(application.getString(R.string.errorUnknown))

        viewModelScope.launch(Dispatchers.IO) {
            // show a loading indicator
            _showLoader.value = true

            // delete remotely
            try {
                repository.deleteNoteToSelfMessagesRemotely(threadId, recipient!!.address, data.messages)

                // When this is done we simply need to remove the message locally (leave nothing behind)
                repository.deleteMessages(messages = data.messages, threadId = threadId)

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
        if(recipient == null) return showMessage(application.getString(R.string.errorUnknown))

        viewModelScope.launch(Dispatchers.IO) {
            // show a loading indicator
            _showLoader.value = true

            // delete remotely
            try {
                repository.delete1on1MessagesRemotely(threadId, recipient!!.address, data.messages)

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
        if(recipient == null) return showMessage(application.getString(R.string.errorUnknown))

        viewModelScope.launch(Dispatchers.IO) {
            // delete remotely
            try {
                repository.deleteLegacyGroupMessagesRemotely(recipient!!.address, messages)

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
                repository.deleteGroupV2MessagesRemotely(recipient!!.address, data.messages)

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
                repository.deleteCommunityMessagesRemotely(threadId, data.messages)

                // When this is done we simply need to remove the message locally (leave nothing behind)
                repository.deleteMessages(messages = data.messages, threadId = threadId)

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

    private fun isUserCommunityManager(openGroup: OpenGroup?) = openGroup?.let { openGroup ->
        val userPublicKey = textSecurePreferences.getLocalNumber() ?: return@let false
        openGroupManager.isUserModerator(openGroup.id, userPublicKey, blindedPublicKey)
    } ?: false

    /**
     * Stops audio player if its current playing is the one given in the message.
     */
    private fun stopMessageAudio(audioSlide: AudioSlide) {
        AudioSlidePlayer.getInstance()?.takeIf { it.audioSlide == audioSlide }?.stop()
    }

    fun banUser(recipient: Address) = viewModelScope.launch {
        repository.banUser(threadId, recipient)
            .onSuccess {
                showMessage(application.getString(R.string.banUserBanned))
            }
            .onFailure {
                showMessage(application.getString(R.string.banErrorFailed))
            }
    }

    fun banAndDeleteAll(messageRecord: MessageRecord) = viewModelScope.launch {

        repository.banAndDeleteAll(threadId, messageRecord.individualRecipient.address)
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
        val recipient = recipient ?: return@launch Log.w("Loki", "Recipient was null for accept message request action")
        val currentState = messageRequestState.value as? MessageRequestUiState.Visible
            ?: return@launch Log.w("Loki", "Current state was not visible for accept message request action")

        _acceptingMessageRequest.value = true

        repository.acceptMessageRequest(threadId, recipient.address)
            .onFailure {
                Log.w("Loki", "Couldn't accept message request due to error", it)
                _acceptingMessageRequest.value = false
            }
    }

    fun declineMessageRequest() = viewModelScope.launch {
        repository.declineMessageRequest(threadId, recipient!!.address)
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

    fun legacyBannerRecipient(context: Context): Recipient? = recipient?.run {
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
        val recipient = recipient

        if (messageRequestState.value is MessageRequestUiState.Visible) {
            return acceptMessageRequest()
        } else if (recipient?.approved == false) {
            // edge case for new outgoing thread on new recipient without sending approval messages
            repository.setApproved(recipient.address, true)
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
                        recreateGroupData = recipient?.address?.toString()?.let { addr -> RecreateGroupDialogData(legacyGroupId = addr) }
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
        }
    }

    private fun clearEmoji(emoji: String, messageId: MessageId){
        viewModelScope.launch(Dispatchers.Default) {
            reactionDb.deleteEmojiReactions(emoji, messageId)
            openGroup?.let { openGroup ->
                lokiMessageDb.getServerID(messageId)?.let { serverId ->
                    OpenGroupApi.deleteAllReactions(
                        openGroup.room,
                        openGroup.server,
                        serverId,
                        emoji
                    )
                }
            }
            threadDb.notifyThreadUpdated(threadId)
        }
    }

    fun onEmojiClear(emoji: String, messageId: MessageId) {
        // show a confirmation dialog
        _dialogsState.update {
            it.copy(clearAllEmoji = ClearAllEmoji(emoji, messageId))
        }
    }

    fun getUsername(accountId: String) = recipientRepository
        .getRecipientDisplayNameSync(fromSerialized(accountId))

    fun onSearchOpened() {
        _searchOpened.value = true
    }

    fun onSearchClosed(){
        _searchOpened.value = false
    }

    private fun showDisappearingMessages() {
        recipient?.let { convo ->
            if (convo.isLegacyGroupRecipient) {
                groupDb.getGroup(convo.address.toGroupString()).orNull()?.run {
                    if (!isActive) return
                }
            }

            _uiEvents.tryEmit(ConversationUiEvent.ShowDisappearingMessages(address))
        }
    }

    private fun showGroupMembers() {
        recipient?.let { convo ->
            val groupId = recipient?.address?.toString() ?: return

            _uiEvents.tryEmit(ConversationUiEvent.ShowGroupMembers(groupId))
        }
    }

    private fun showNotificationSettings() {
        _uiEvents.tryEmit(ConversationUiEvent.ShowNotificationSettings(address))
    }


    @AssistedFactory
    interface Factory {
        fun create(address: Address): ConversationViewModel
    }

    data class DialogsState(
        val openLinkDialogUrl: String? = null,
        val clearAllEmoji: ClearAllEmoji? = null,
        val deleteEveryone: DeleteForEveryoneDialogData? = null,
        val recreateGroupConfirm: Boolean = false,
        val recreateGroupData: RecreateGroupDialogData? = null,
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
        data class NavigateToConversation(val address: Address) : Commands
    }
}

data class UiMessage(val id: Long, val message: String)


sealed interface ConversationUiEvent {
    data class NavigateToConversation(val address: Address) : ConversationUiEvent
    data class ShowDisappearingMessages(val address: Address) : ConversationUiEvent
    data class ShowNotificationSettings(val address: Address) : ConversationUiEvent
    data class ShowGroupMembers(val groupId: String) : ConversationUiEvent
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
