package org.thoughtcrime.securesms.home

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import org.session.libsession.utilities.Phrase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.libsession_util.PRIORITY_HIDDEN
import org.session.libsession.database.StorageProtocol
import org.session.libsession.network.SnodeClock
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.CommunityUrlParser
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.displayName
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.audio.AudioPlaybackManager
import org.thoughtcrime.securesms.audio.model.AudioPlaybackState
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.debugmenu.DebugLogGroup
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.notifications.NotificationPreferences.CHECKED_DOZE_WHITELIST
import org.thoughtcrime.securesms.notifications.NotificationPreferences.PUSH_ENABLED
import org.thoughtcrime.securesms.groups.OpenGroupManager
import org.thoughtcrime.securesms.links.LinkType
import org.thoughtcrime.securesms.onboarding.OnBoardingPreferences.HAS_VIEWED_SEED
import org.thoughtcrime.securesms.preferences.AppPreferences
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination
import org.thoughtcrime.securesms.pro.ProRefreshWindows
import org.thoughtcrime.securesms.pro.ProStatus
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.sskenvironment.TypingStatusRepository
import org.thoughtcrime.securesms.ui.dialog.SimpleDialogData
import org.thoughtcrime.securesms.ui.isWhitelistedFromDoze
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.DonationManager
import org.thoughtcrime.securesms.util.DonationManager.Companion.URL_DONATE
import org.thoughtcrime.securesms.util.UserProfileModalCommands
import org.thoughtcrime.securesms.util.UserProfileModalData
import org.thoughtcrime.securesms.util.UserProfileUtils
import org.thoughtcrime.securesms.webrtc.CallManager
import org.thoughtcrime.securesms.webrtc.data.State
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: TextSecurePreferences,
    private val prefStorage: PreferenceStorage,
    private val loginStateRepository: LoginStateRepository,
    private val typingStatusRepository: TypingStatusRepository,
    private val configFactory: ConfigFactory,
    callManager: CallManager,
    private val storage: StorageProtocol,
    private val groupManager: GroupManagerV2,
    private val conversationRepository: ConversationRepository,
    private val proStatusManager: ProStatusManager,
    private val upmFactory: UserProfileUtils.UserProfileUtilsFactory,
    private val recipientRepository: RecipientRepository,
    private val dateUtils: DateUtils,
    private val snodeClock: SnodeClock,
    private val donationManager: DonationManager,
    private val audioPlaybackManager: AudioPlaybackManager,
    private val openGroupManager: OpenGroupManager,
) : ViewModel() {
    // SharedFlow that emits whenever the user asks us to reload  the conversation
    private val manualReloadTrigger = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val mutableIsSearchOpen = MutableStateFlow(false)
    val isSearchOpen: StateFlow<Boolean> get() = mutableIsSearchOpen

    val callBanner: StateFlow<String?> = callManager.currentConnectionStateFlow.map {
        // a call is in progress if it isn't idle nor disconnected
        if (it !is State.Idle && it !is State.Disconnected) {
            // call is started, we need to differentiate between in progress vs incoming
            if (it is State.Connected) context.getString(R.string.callsInProgress)
            else context.getString(R.string.callsIncomingUnknown)
        } else null // null when the call isn't in progress / incoming
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), initialValue = null)

    private val _dialogsState = MutableStateFlow(DialogsState())
    val dialogsState: StateFlow<DialogsState> = _dialogsState

    private val _uiEvents = MutableSharedFlow<UiEvent>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val uiEvents: SharedFlow<UiEvent> = _uiEvents

    val audioPlaybackState: StateFlow<AudioPlaybackState> = audioPlaybackManager.playbackState

    /**
     * A [StateFlow] that emits the list of threads and the typing status of each thread.
     *
     * This flow will emit whenever the user asks us to reload the conversation list or
     * whenever the conversation list changes.
     */
    @Suppress("OPT_IN_USAGE")
    val data: StateFlow<Data?> = (combine(
        // First flow: conversation list and unapproved conversation count
        manualReloadTrigger
            .onStart { emit(Unit) }
            .flatMapLatest {
                conversationRepository.observeConversationList()
            }
            .map { convos ->
                val (approved, unapproved) = convos
                    .asSequence()
                    .filter { !it.recipient.blocked } // We don't display blocked convo
                    .filter { it.recipient.priority != PRIORITY_HIDDEN } // We don't show hidden convo
                    .partition { it.recipient.approved }
                val unreadUnapproved = unapproved
                    .count { it.unreadCount > 0 || it.unreadMentionCount > 0 }
                unreadUnapproved to approved.sortedWith(CONVERSATION_COMPARATOR)
            },

        // Second flow: typing status of threads
        observeTypingStatus(),

        // Third flow: whether the user has marked message requests as hidden
        prefStorage.watch(viewModelScope, AppPreferences.HAS_HIDDEN_MESSAGE_REQUESTS),
    ) { (unapproveConvoCount, convoList), typingStatus, hiddenMessageRequest ->
        // check if we should show the recovery phrase backup banner:
        // - if the user has not yet seen the warning
        // - if the user has at least 3 conversations
        if (!prefStorage[HAS_VIEWED_SEED] && convoList.size >= 3){
            _uiState.update {
                it.copy(showRecoveryPhraseBackupBanner = true)
            }
        }

        Data(
            items = buildList {
                if (unapproveConvoCount > 0 && !hiddenMessageRequest) {
                    add(Item.MessageRequests(unapproveConvoCount))
                }

                convoList.mapTo(this) { thread ->
                    Item.Thread(
                        thread = thread,
                        isTyping = typingStatus.contains(thread.threadId),
                    )
                }
            }
        )
    } as Flow<Data?>).catch { err ->
        Log.e("HomeViewModel", "Error loading conversation list", err)
        emit(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _uiState = MutableStateFlow<UIState>(UIState())
    val uiState: StateFlow<UIState> = _uiState

    private var userProfileModalJob: Job? = null
    private var userProfileModalUtils: UserProfileUtils? = null

    init {
        // check for white list status in case of slow mode
        if(!prefStorage[CHECKED_DOZE_WHITELIST] // the user has not yet seen the dialog
            && !prefStorage[PUSH_ENABLED] // the user is in slow mode
            && !context.isWhitelistedFromDoze() // the user isn't yet whitelisted
        ){
            prefStorage[CHECKED_DOZE_WHITELIST] = true
            viewModelScope.launch {
                delay(1500)
                _dialogsState.update {
                    it.copy(
                        showSimpleDialog = SimpleDialogData(
                            title = Phrase.from(context, R.string.runSessionBackground)
                                .format().toString(),
                            message = Phrase.from(context, R.string.runSessionBackgroundDescription)
                                .format().toString(),
                            positiveText = context.getString(R.string.allow),
                            negativeText = context.getString(R.string.cancel),
                            positiveQaTag = context.getString(R.string.qa_conversation_settings_dialog_whitelist_confirm),
                            negativeQaTag = context.getString(R.string.qa_conversation_settings_dialog_whitelist_cancel),
                            positiveStyleDanger = false,
                            onPositive = {
                                // show system whitelist dialog
                                viewModelScope.launch {
                                    _uiEvents.emit(UiEvent.ShowWhiteListSystemDialog)
                                }
                            },
                            onNegative = {}
                        )
                    )
                }
            }
        }

        // observe subscription status
        viewModelScope.launch {
            proStatusManager.proDataState.collect { subscription ->
                // show a CTA when
                // - subscription is expiring in less than 7 days
                // - subscription expired less than 30 days ago
                //
                // Armed ONCE PER PRO CYCLE, not once per install and not once per launch: Pro can only
                // expire once per cycle, so one warning per cycle is the whole intent. The latch is
                // persisted (`has_seen_pro_expir{ing,ed}`), written on DISMISSAL rather than on display
                // -- so a CTA shown to a process that dies before the user dismisses it is shown again --
                // and cleared below whenever the account reads Active again.
                //
                // Do not "correct" this to fire on every launch or every status change. Both were
                // considered and rejected: the arm-once-per-cycle model is the ruled behaviour, and it is
                // the one the other clients are being aligned TO.
                // Network time: both comparisons below are against instants the backend supplied,
                // so device-clock skew moves the boundary rather than the subscription. The two CTAs
                // also skew in opposite directions — a fast clock warns of expiry early and stops
                // warning of expiration early — so a wrong reading here is not uniformly wrong.
                val now = snodeClock.currentTime()

                var showExpiring: Boolean = false
                var showExpired: Boolean = false

                if(subscription.type is ProStatus.Active &&
                    (prefs.hasSeenProExpiring() || prefs.hasSeenProExpired())){
                    prefs.clearProExpiryView() // reset expiry view if the user is active again
                } else if(subscription.type is ProStatus.Active.Expiring
                    // Same confirmed-fetch gate as the Expired branch below; both read the one
                    // `refreshState` predicate, so tightening or loosening it moves both CTAs.
                    // Success means THIS process has had a fetch confirmed, so a cold launch cannot
                    // warn off a stale local proof: `status` is inferred from that proof at launch, and
                    // nothing writes to config at renewal, so a single-device account that renewed
                    // while the app was closed reads as expiring until get_pro_status says otherwise.
                    // Consistent with the iOS fix, which gates both variants above the switch.
                    && subscription.refreshState is org.thoughtcrime.securesms.util.State.Success
                    && !prefs.hasSeenProExpiring()
                ){
                    val validUntil = subscription.type.renewingAt
                    showExpiring = validUntil.isBefore(now.plus(ProRefreshWindows.EXPIRING_CTA))
                    Log.d(DebugLogGroup.PRO_DATA.label, "Home: Pro active but not auto renewing (expiring). Valid until: $validUntil - Should show Expiring CTA? $showExpiring")
                    if (showExpiring) {
                        _dialogsState.update { state ->
                            state.copy(
                                proExpiringCTA = ProExpiringCTA(
                                    dateUtils.getExpiryString(validUntil, now)
                                )
                            )
                        }
                    }
                }
                // WithPlan, because the window below is measured from a date only a response carries.
                // An expired status derived from local state has no coverage-end instant, and there is
                // no safe stand-in: a sentinel puts the window in the past, which suppresses the CTA
                // without any sign that a date was missing.
                else if(subscription.type is ProStatus.Expired.WithPlan
                    // Only after a SUCCESSFUL get_pro_status request (the round-trip completed and the
                    // backend answered — even if with "expired"/"not pro"), never off stale data from a
                    // failed or in-flight fetch: on foreground the cached status can be pre-renewal, and a
                    // network failure must not surface a false "expired". Consistent with the iOS fix.
                    && subscription.refreshState is org.thoughtcrime.securesms.util.State.Success
                    && !prefs.hasSeenProExpired()) {
                    // Anchored at coverage end, not at the payment date: the backend only reports
                    // EXPIRED once coverage has ended, so measuring from the payment date shortens
                    // this window by exactly the grace period and empties it entirely when grace is
                    // 30 days or more.
                    val coverageEnded = subscription.type.coverageEndedAt
                    showExpired = now.isBefore(coverageEnded.plus(ProRefreshWindows.EXPIRED_CTA))

                    Log.d(DebugLogGroup.PRO_DATA.label, "Home: Pro expired. Coverage ended: $coverageEnded - Should show Expired CTA? $showExpired")

                    if (showExpired) {
                        _dialogsState.update { state ->
                            state.copy(proExpiredCTA = true)
                        }
                    }
                }

                // check if we should display the donation CTA - unless we have a pro CTA already
                if(!showExpiring && !showExpired && donationManager.shouldShowDonationCTA()){
                    showDonationCTA()
                }
            }
        }

        // observe current user's recipient data change
        viewModelScope.launch {
            recipientRepository.observeSelf().collect {
                _uiState.update { state ->
                    state.copy(
                        showCurrentUserProBadge = it.isPro
                    )
                }
            }
        }
    }

    private fun observeTypingStatus(): Flow<Set<Long>> = typingStatusRepository
        .typingThreads
        .asFlow()
        .onStart { emit(emptySet()) }
        .distinctUntilChanged()


    fun tryReload(): Boolean = manualReloadTrigger.tryEmit(Unit)

    fun onSearchClicked() {
        mutableIsSearchOpen.value = true
    }

    fun onCancelSearchClicked() {
        mutableIsSearchOpen.value = false
    }

    fun onBackPressed(): Boolean {
        if (mutableIsSearchOpen.value) {
            mutableIsSearchOpen.value = false
            return true
        }

        return false
    }

    data class Data(
        val items: List<Item>,
    )

    sealed interface Item {
        data class Thread(
            val thread: ThreadRecord,
            val isTyping: Boolean,
        ) : Item

        data class MessageRequests(val count: Int) : Item
    }


    fun blockContact(accountId: String) {
        viewModelScope.launch(Dispatchers.Default) {
            storage.setBlocked(listOf(Address.fromSerialized(accountId)), isBlocked = true)
        }
    }

    fun deleteContact(address: Address.WithAccountId) {
        configFactory.removeContactOrBlindedContact(address)
    }

    fun leaveGroup(accountId: AccountId, deleteGroup : Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            groupManager.leaveGroup(accountId, deleteGroup)
        }
    }

    fun setPinned(address: Address, pinned: Boolean) {
        // check the pin limit before continuing
        val totalPins = storage.getTotalPinned()
        // ENFORCEMENT: the ACCESS function, called at the moment of the decision. Was a full
        // `getSelf().isPro` resolve, which reaches the same answer but by a second route — one function
        // means a revocation or expiry cannot be honoured here and missed on the send path.
        val maxPins =
            proStatusManager.getPinnedConversationLimit(proStatusManager.currentUserHasProAccess())
        if (pinned && totalPins >= maxPins) {
            // No upsell when the plan already reads active. The pin was refused above on access, and
            // a subscriber whose proof has not arrived would otherwise be offered the plan they hold.
            // The refusal is therefore silent: the copy that would explain it without offering a
            // purchase does not exist, and a silent refusal is the lesser of the two.
            if (proStatusManager.proDataState.value.type is ProStatus.Active) return
            // the user has reached the pin limit, show the CTA
            _dialogsState.update {
                it.copy(
                    pinCTA = PinProCTA(
                        overTheLimit = totalPins > maxPins,
                        proSubscription = proStatusManager.proDataState.value.type
                    )
                )
            }
        } else {
            viewModelScope.launch(Dispatchers.Default) {
                storage.setPinned(address, pinned)
            }
        }
    }

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.HidePinCTADialog -> {
                _dialogsState.update { it.copy(pinCTA = null) }
            }

            is Commands.HideUserProfileModal -> {
                _dialogsState.update { it.copy(userProfileModal = null) }
            }

            is Commands.HandleUserProfileCommand -> {
                userProfileModalUtils?.onCommand(command.upmCommand)
            }

            is Commands.ShowStartConversationSheet -> {
                _dialogsState.update { it.copy(showStartConversationSheet =
                    StartConversationSheetData(
                        accountId = loginStateRepository.requireLocalNumber()
                    )
                ) }
            }

            is Commands.HideStartConversationSheet -> {
                _dialogsState.update { it.copy(showStartConversationSheet = null) }
            }

            is Commands.HideExpiringCTADialog -> {
                prefs.setHasSeenProExpiring()
                _dialogsState.update { it.copy(proExpiringCTA = null) }
            }

            is Commands.HideExpiredCTADialog -> {
                prefs.setHasSeenProExpired()
                _dialogsState.update { it.copy(proExpiredCTA = false) }
            }

            is Commands.GotoProSettings -> {
                viewModelScope.launch {
                    _uiEvents.emit(UiEvent.OpenProSettings(command.destination))
                }
            }

            is Commands.HideSimpleDialog -> {
                _dialogsState.update { it.copy(showSimpleDialog = null) }
            }

            is Commands.HideDonationCTADialog -> {
                _dialogsState.update { it.copy(donationCTA = false) }
            }

            is Commands.OnDonationLinkClicked -> {
                donationManager.onDonationSeen()
                _dialogsState.update { it.copy(donationCTA = false) }
            }

            is Commands.HideUrlDialog -> {
                _dialogsState.update { it.copy(urlDialog = null) }
            }

            is Commands.OnLinkOpened -> {
                // if the link was for donation, mark it as seen
                if(command.url == URL_DONATE) {
                    donationManager.onDonationSeen()
                }
            }

            is Commands.OnLinkCopied -> {
                // if the link was for donation, mark it as seen
                if(command.url == URL_DONATE) {
                    donationManager.onDonationCopied()
                }
            }

            is Commands.OpenOrJoinCommunity -> openOrJoinCommunity(command.url)

            is Commands.ShowUrlDialog -> {
                _dialogsState.update { it.copy(urlDialog = command.linkType) }
            }

            is Commands.ShowNewConversationConfirmationDialog -> {
                _dialogsState.update {
                    it.copy(
                        showSimpleDialog = SimpleDialogData(
                            title = context.getString(R.string.conversationsStart),
                            message = context.getString(R.string.globalSearchAccountId),
                            negativeText = context.getString(R.string.conversationsStart),
                            positiveText = context.getString(R.string.cancel),
                            positiveStyleDanger = false,
                            onNegative = {
                                viewModelScope.launch {
                                    _uiEvents.emit(UiEvent.OpenConversation(command.address))
                                }
                            },
                            onPositive = {
                                onCommand(Commands.HideSimpleDialog)
                            },
                        )
                    )
                }
            }
        }
    }

    private fun openOrJoinCommunity(url: String) {
        val communityInfo = try {
            CommunityUrlParser.parse(url)
        } catch (_: CommunityUrlParser.Error) {
            Toast.makeText(context, R.string.communityEnterUrlErrorInvalidDescription, Toast.LENGTH_SHORT)
                .show()
            return
        }

        _dialogsState.update { it.copy(urlDialog = null) }
        mutableIsSearchOpen.value = false

        viewModelScope.launch {
            try {
                openGroupManager.add(
                    server = communityInfo.baseUrl,
                    room = communityInfo.room,
                    publicKey = communityInfo.pubKeyHex,
                )

                // after joining or if already joined, open the conversation
                val communityAddress = Address.Community(communityInfo.baseUrl, communityInfo.room)
                _uiEvents.emit(UiEvent.OpenConversation(communityAddress))

            } catch (e: Exception) {
                Log.e("HomeViewModel",  "Error joining community", e)
                Toast.makeText(context, R.string.communityErrorDescription, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    fun showDonationCTA(){
        _dialogsState.update { it.copy(donationCTA = true) }
        donationManager.onDonationCTAViewed()
    }

    fun showUrlDialog(url: String) {
        _dialogsState.update { it.copy(urlDialog = LinkType.GenericLink(url)) }
    }


    fun showUserProfileModal(thread: ThreadRecord) {
        // get the helper class for the selected user
        userProfileModalUtils = upmFactory.create(
            userAddress = thread.recipient.address,
            threadAddress = thread.recipient.address as Address.Conversable,
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

    fun getLeaveGroupConfirmationDialog(thread: ThreadRecord, isDeleteGroup : Boolean): GroupManagerV2.ConfirmDialogData? {
        val recipient = thread.recipient
        if (recipient.address is Address.Group) {
            val accountId = recipient.address.accountId
            // Admin will delete the group
            return if (isDeleteGroup) {
                groupManager.getDeleteGroupConfirmationDialogData(
                    accountId,
                    recipient.displayName()
                )
            } else {
                // more than 1 admin will leave
                groupManager.getLeaveGroupConfirmationDialogData(
                    accountId,
                    recipient.displayName()
                )
            }
        }

        return null
    }

    fun isCurrentUserLastAdmin(groupId : AccountId) : Boolean{
        return groupManager.isCurrentUserLastAdmin(groupId)
    }

    fun stopAudio(){
        audioPlaybackManager.stop()
    }

    fun togglePlayPause(){
        audioPlaybackManager.togglePlayPause()
    }

    fun cyclePlaybackSpeed(){
        audioPlaybackManager.cyclePlaybackSpeed()
    }

    data class DialogsState(
        val pinCTA: PinProCTA? = null,
        val userProfileModal: UserProfileModalData? = null,
        val showStartConversationSheet: StartConversationSheetData? = null,
        val proExpiringCTA: ProExpiringCTA? = null,
        val proExpiredCTA: Boolean = false,
        val showSimpleDialog: SimpleDialogData? = null,
        val donationCTA: Boolean = false,
        val urlDialog: LinkType? = null,
    )

    data class PinProCTA(
        val overTheLimit: Boolean,
        val proSubscription: ProStatus
    )

    data class ProExpiringCTA(
        val expiry: String
    )

    data class StartConversationSheetData(
        val accountId: String
    )

    sealed interface UiEvent {
        data class OpenConversation(val address: Address.Conversable) : UiEvent
        data class OpenProSettings(val start: ProSettingsDestination) : UiEvent
        data object ShowWhiteListSystemDialog: UiEvent // once confirmed, this is for the system whitelist dialog
    }

    data class UIState(
        val showCurrentUserProBadge: Boolean = false,
        val showRecoveryPhraseBackupBanner: Boolean = false
    )

    sealed interface Commands {
        data object HidePinCTADialog : Commands
        data object HideExpiringCTADialog : Commands
        data object HideExpiredCTADialog : Commands
        data object OnDonationLinkClicked : Commands
        data object HideDonationCTADialog : Commands
        data object HideUserProfileModal : Commands
        data object HideUrlDialog : Commands
        data class ShowUrlDialog(val linkType: LinkType) : Commands
        data class ShowNewConversationConfirmationDialog(val address: Address.Conversable) : Commands
        data class OnLinkOpened(val url: String) : Commands
        data class OnLinkCopied(val url: String) : Commands
        data class OpenOrJoinCommunity(val url: String) : Commands
        data class HandleUserProfileCommand(
            val upmCommand: UserProfileModalCommands
        ) : Commands

        data object ShowStartConversationSheet : Commands
        data object HideStartConversationSheet : Commands

        data object HideSimpleDialog: Commands

        data class GotoProSettings(
            val destination: ProSettingsDestination
        ): Commands
    }

    companion object {
        private val CONVERSATION_COMPARATOR = compareByDescending<ThreadRecord> { it.recipient.isPinned }
            .thenByDescending { it.recipient.priority }
            .thenByDescending { it.date }
            .thenByDescending { it.lastMessage?.timestamp ?: 0L }
            .thenBy { it.recipient.displayName() }
    }
}
