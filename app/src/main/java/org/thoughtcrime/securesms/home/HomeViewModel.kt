package org.thoughtcrime.securesms.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.libsession_util.PRIORITY_HIDDEN
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.displayName
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.debugmenu.DebugLogGroup
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsDestination
import org.thoughtcrime.securesms.pro.ProStatus
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.sskenvironment.TypingStatusRepository
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.DonationManager
import org.thoughtcrime.securesms.util.DonationManager.Companion.URL_DONATE
import org.thoughtcrime.securesms.util.UserProfileModalCommands
import org.thoughtcrime.securesms.util.UserProfileModalData
import org.thoughtcrime.securesms.util.UserProfileUtils
import org.thoughtcrime.securesms.webrtc.CallManager
import org.thoughtcrime.securesms.webrtc.data.State
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: TextSecurePreferences,
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
    private val donationManager: DonationManager
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
        (TextSecurePreferences.events.filter { it == TextSecurePreferences.HAS_HIDDEN_MESSAGE_REQUESTS } as Flow<*>)
            .onStart { emit(Unit) }
            .map { prefs.hasHiddenMessageRequests() }
    ) { (unapproveConvoCount, convoList), typingStatus, hiddenMessageRequest ->
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

    val shouldShowCurrentUserProBadge: StateFlow<Boolean> = recipientRepository
        .observeSelf()
        .map { it.shouldShowProBadge }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var userProfileModalJob: Job? = null
    private var userProfileModalUtils: UserProfileUtils? = null

    init {
        // observe subscription status
        viewModelScope.launch {
            proStatusManager.proDataState.collect { subscription ->
                // show a CTA (only once per install) when
                // - subscription is expiring in less than 7 days
                // - subscription expired less than 30 days ago
                val now = Instant.now()

                if(subscription.type is ProStatus.Active.Expiring
                    && !prefs.hasSeenProExpiring()
                ){
                    val validUntil = subscription.type.validUntil
                    val show = validUntil.isBefore(now.plus(7, ChronoUnit.DAYS))
                    Log.d(DebugLogGroup.PRO_DATA.label, "Home: Pro active but not auto renewing (expiring). Valid until: $validUntil - Should show Expiring CTA? $show")
                    if (show) {
                        _dialogsState.update { state ->
                            state.copy(
                                proExpiringCTA = ProExpiringCTA(
                                    dateUtils.getExpiryString(validUntil)
                                )
                            )
                        }
                    }
                }
                else if(subscription.type is ProStatus.Expired
                    && !prefs.hasSeenProExpired()) {
                    val validUntil = subscription.type.expiredAt
                    val show = now.isBefore(validUntil.plus(30, ChronoUnit.DAYS))

                    Log.d(DebugLogGroup.PRO_DATA.label, "Home: Pro expired. Expired at: $validUntil - Should show Expired CTA? $show")

                    // Check if now is within 30 days after expiry
                    if (show) {

                        _dialogsState.update { state ->
                            state.copy(proExpiredCTA = true)
                        }
                    }
                }
            }
        }

        // check if we should display the donation CTA
        if(donationManager.shouldShowDonationCTA()){
            showDonationCTA()
        }
    }

    private fun observeTypingStatus(): Flow<Set<Long>> = typingStatusRepository
        .typingThreads
        .asFlow()
        .onStart { emit(emptySet()) }
        .distinctUntilChanged()


    fun tryReload() = manualReloadTrigger.tryEmit(Unit)

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

    fun leaveGroup(accountId: AccountId) {
        viewModelScope.launch(Dispatchers.Default) {
            groupManager.leaveGroup(accountId)
        }
    }

    fun setPinned(address: Address, pinned: Boolean) {
        // check the pin limit before continuing
        val totalPins = storage.getTotalPinned()
        val maxPins = proStatusManager.getPinnedConversationLimit(recipientRepository.getSelf().isPro)
        if (pinned && totalPins >= maxPins) {
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

            is Commands.HideDonationCTADialog -> {
                _dialogsState.update { it.copy(donationCTA = false) }
            }

            is Commands.ShowDonationConfirmation -> {
                showUrlDialog(URL_DONATE)
            }

            is Commands.HideUrlDialog -> {
                _dialogsState.update { it.copy(showUrlDialog = null) }
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
        }
    }

    fun showDonationCTA(){
        _dialogsState.update { it.copy(donationCTA = true) }
        donationManager.onDonationCTAViewed()
    }

    fun showUrlDialog(url: String) {
        _dialogsState.update { it.copy(showUrlDialog = url) }
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

    data class DialogsState(
        val pinCTA: PinProCTA? = null,
        val userProfileModal: UserProfileModalData? = null,
        val showStartConversationSheet: StartConversationSheetData? = null,
        val proExpiringCTA: ProExpiringCTA? = null,
        val proExpiredCTA: Boolean = false,
        val donationCTA: Boolean = false,
        val showUrlDialog: String? = null,
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
        data class OpenProSettings(val start: ProSettingsDestination) : UiEvent
    }

    sealed interface Commands {
        data object HidePinCTADialog : Commands
        data object HideExpiringCTADialog : Commands
        data object HideExpiredCTADialog : Commands
        data object ShowDonationConfirmation : Commands
        data object HideDonationCTADialog : Commands
        data object HideUserProfileModal : Commands
        data object HideUrlDialog : Commands
        data class OnLinkOpened(val url: String) : Commands
        data class OnLinkCopied(val url: String) : Commands
        data class HandleUserProfileCommand(
            val upmCommand: UserProfileModalCommands
        ) : Commands

        data object ShowStartConversationSheet : Commands
        data object HideStartConversationSheet : Commands

        data class GotoProSettings(
            val destination: ProSettingsDestination
        ): Commands
    }

    companion object {
        private val CONVERSATION_COMPARATOR = compareByDescending<ThreadRecord> { it.recipient.isPinned }
            .thenByDescending { it.recipient.priority }
            .thenByDescending { it.lastMessage?.timestamp ?: 0L }
            .thenByDescending { it.date }
            .thenBy { it.recipient.displayName() }
    }
}
