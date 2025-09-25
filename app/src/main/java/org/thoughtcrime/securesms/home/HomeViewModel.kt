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
import network.loki.messenger.R
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.displayName
import org.session.libsession.utilities.recipients.shouldShowProBadge
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.sskenvironment.TypingStatusRepository
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
    private val typingStatusRepository: TypingStatusRepository,
    private val configFactory: ConfigFactory,
    callManager: CallManager,
    private val storage: StorageProtocol,
    private val groupManager: GroupManagerV2,
    private val conversationRepository: ConversationRepository,
    private val proStatusManager: ProStatusManager,
    private val upmFactory: UserProfileUtils.UserProfileUtilsFactory,
    private val recipientRepository: RecipientRepository,
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
        .map { it.proStatus.shouldShowProBadge() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var userProfileModalJob: Job? = null
    private var userProfileModalUtils: UserProfileUtils? = null

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
        val maxPins = proStatusManager.getPinnedConversationLimit(recipientRepository.getSelf().proStatus)
        if (pinned && totalPins >= maxPins) {
            // the user has reached the pin limit, show the CTA
            _dialogsState.update {
                it.copy(
                    pinCTA = PinProCTA(overTheLimit = totalPins > maxPins)
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
                        accountId = prefs.getLocalNumber()!!
                    )
                ) }
            }

            is Commands.HideStartConversationSheet -> {
                _dialogsState.update { it.copy(showStartConversationSheet = null) }
            }
        }
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
        val showStartConversationSheet: StartConversationSheetData? = null
    )

    data class PinProCTA(
        val overTheLimit: Boolean
    )

    data class StartConversationSheetData(
        val accountId: String
    )

    sealed interface Commands {
        data object HidePinCTADialog : Commands
        data object HideUserProfileModal : Commands
        data class HandleUserProfileCommand(
            val upmCommand: UserProfileModalCommands
        ) : Commands

        data object ShowStartConversationSheet : Commands
        data object HideStartConversationSheet : Commands
    }

    companion object {
        private val CONVERSATION_COMPARATOR = compareByDescending<ThreadRecord> { it.recipient.isPinned }
            .thenByDescending { it.recipient.priority }
            .thenByDescending { it.lastMessage?.timestamp ?: 0L }
            .thenByDescending { it.date }
            .thenBy { it.recipient.displayName() }
    }
}
