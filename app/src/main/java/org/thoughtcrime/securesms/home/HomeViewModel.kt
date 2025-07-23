package org.thoughtcrime.securesms.home

import android.content.ContentResolver
import android.content.Context
import androidx.annotation.AttrRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UsernameUtils
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.DatabaseContentProviders
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.reviews.InAppReviewManager
import org.thoughtcrime.securesms.sskenvironment.TypingStatusRepository
import org.thoughtcrime.securesms.util.UserProfileModalCommands
import org.thoughtcrime.securesms.util.UserProfileModalData
import org.thoughtcrime.securesms.util.UserProfileUtils
import org.thoughtcrime.securesms.util.observeChanges
import org.thoughtcrime.securesms.webrtc.CallManager
import org.thoughtcrime.securesms.webrtc.data.State
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val threadDb: ThreadDatabase,
    private val contentResolver: ContentResolver,
    private val prefs: TextSecurePreferences,
    private val typingStatusRepository: TypingStatusRepository,
    private val configFactory: ConfigFactory,
    private val callManager: CallManager,
    private val usernameUtils: UsernameUtils,
    private val storage: StorageProtocol,
    private val groupManager: GroupManagerV2,
    private val proStatusManager: ProStatusManager,
    private val upmFactory: UserProfileUtils.UserProfileUtilsFactory,
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
        if(it !is State.Idle && it !is State.Disconnected){
            // call is started, we need to differentiate between in progress vs incoming
            if(it is State.Connected) context.getString(R.string.callsInProgress)
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
    val data: StateFlow<Data?> = combine(
        observeConversationList(),
        observeTypingStatus(),
        messageRequests(),
        hasHiddenNoteToSelf()
    ) { threads, typingStatus, messageRequests, hideNoteToSelf ->
        Data(
            items = buildList {
                messageRequests?.let { add(it) }

                threads.mapNotNullTo(this) { thread ->
                    // if the note to self is marked as hidden,
                    // or if the contact is blocked, do not add it
                    if (
                        thread.recipient.isLocalNumber && hideNoteToSelf ||
                        thread.recipient.isBlocked
                    ) {
                        return@mapNotNullTo null
                    }

                    Item.Thread(
                        thread = thread,
                        isTyping = typingStatus.contains(thread.threadId),
                    )
                }
            }
        ) as? Data?
    }.catch { err ->
        Log.e("HomeViewModel", "Error loading conversation list", err)
        emit(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var userProfileModalJob: Job? = null
    private var userProfileModalUtils: UserProfileUtils? = null

    private fun hasHiddenMessageRequests() = TextSecurePreferences.events
        .filter { it == TextSecurePreferences.HAS_HIDDEN_MESSAGE_REQUESTS }
        .map { prefs.hasHiddenMessageRequests() }
        .onStart { emit(prefs.hasHiddenMessageRequests()) }

    private fun hasHiddenNoteToSelf() = TextSecurePreferences.events
        .filter { it == TextSecurePreferences.HAS_HIDDEN_NOTE_TO_SELF }
        .map { prefs.hasHiddenNoteToSelf() }
        .onStart { emit(prefs.hasHiddenNoteToSelf()) }

    private fun observeTypingStatus(): Flow<Set<Long>> = typingStatusRepository
                    .typingThreads
                    .asFlow()
                    .onStart { emit(emptySet()) }
                    .distinctUntilChanged()

    private fun messageRequests() = combine(
        unapprovedConversationCount(),
        hasHiddenMessageRequests(),
        ::createMessageRequests
    ).flowOn(Dispatchers.Default)

    private fun unapprovedConversationCount() = reloadTriggersAndContentChanges()
        .map { threadDb.unapprovedConversationList.use { cursor -> cursor.count } }

    @Suppress("OPT_IN_USAGE")
    private fun observeConversationList(): Flow<List<ThreadRecord>> = reloadTriggersAndContentChanges()
        .mapLatest { _ ->
            threadDb.approvedConversationList.use { openCursor ->
                threadDb.readerFor(openCursor).run { generateSequence { next }.toList() }
            }
        }
        .flowOn(Dispatchers.IO)

    @OptIn(FlowPreview::class)
    private fun reloadTriggersAndContentChanges(): Flow<*> = merge(
        manualReloadTrigger,
        contentResolver.observeChanges(DatabaseContentProviders.ConversationList.CONTENT_URI),
        configFactory.configUpdateNotifications.filterIsInstance<ConfigUpdateNotification.GroupConfigsUpdated>()
    )
        .debounce(CHANGE_NOTIFICATION_DEBOUNCE_MILLS)
        .onStart { emit(Unit) }

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

    data class MessageSnippetOverride(
        val text: CharSequence,
        @AttrRes val colorAttr: Int,
    )

    sealed interface Item {
        data class Thread(
            val thread: ThreadRecord,
            val isTyping: Boolean,
        ) : Item

        data class MessageRequests(val count: Int) : Item
    }

    private fun createMessageRequests(
        count: Int,
        hidden: Boolean
    ) = if (count > 0 && !hidden) Item.MessageRequests(count) else null


    fun hideNoteToSelf() {
        prefs.setHasHiddenNoteToSelf(true)
        configFactory.withMutableUserConfigs {
            it.userProfile.setNtsPriority(PRIORITY_HIDDEN)
        }
    }

    fun getCurrentUsername() = usernameUtils.getCurrentUsernameWithAccountIdFallback()

    fun blockContact(accountId: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val recipient = Recipient.from(context, Address.fromSerialized(accountId), false)
            storage.setBlocked(listOf(recipient), isBlocked = true)
        }
    }

    fun deleteContact(accountId: String) {
        viewModelScope.launch(Dispatchers.Default) {
            storage.deleteContactAndSyncConfig(accountId)
        }
    }

    fun leaveGroup(accountId: AccountId) {
        viewModelScope.launch(Dispatchers.Default) {
            groupManager.leaveGroup(accountId)
        }
    }

    fun setPinned(threadId: Long, pinned: Boolean) {
        // check the pin limit before continuing
        val totalPins = storage.getTotalPinned()
        val maxPins = proStatusManager.getPinnedConversationLimit()
        if(pinned && totalPins >= maxPins){
            // the user has reached the pin limit, show the CTA
            _dialogsState.update {
                it.copy(
                    pinCTA = PinProCTA(overTheLimit = totalPins > maxPins)
                )
            }
        } else {
            viewModelScope.launch(Dispatchers.Default) {
                storage.setPinned(threadId, pinned)
            }
        }
    }

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.HidePinCTADialog -> {
                _dialogsState.update { it.copy(pinCTA = null) }
            }

            is Commands.GoToProUpgradeScreen -> {
                // hide dialog
                _dialogsState.update { it.copy(pinCTA = null) }

                // to go Pro upgrade screen
                //todo PRO go to screen once it exists
            }

            is Commands.HideUserProfileModal -> {
                _dialogsState.update { it.copy(userProfileModal = null) }
            }

            is Commands.HandleUserProfileCommand -> {
                userProfileModalUtils?.onCommand(command.upmCommand)
            }
        }
    }

    fun showUserProfileModal(thread: ThreadRecord) {
        // get the helper class for the selected user
        userProfileModalUtils = upmFactory.create(
            recipient = thread.recipient,
            threadId = thread.threadId,
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
        val userProfileModal: UserProfileModalData? = null
    )

    data class PinProCTA(
        val overTheLimit: Boolean
    )

    sealed interface Commands {
        data object HidePinCTADialog: Commands
        data object HideUserProfileModal: Commands
        data object GoToProUpgradeScreen: Commands
        data class HandleUserProfileCommand(
            val upmCommand: UserProfileModalCommands
        ): Commands
    }

    companion object {
        private const val CHANGE_NOTIFICATION_DEBOUNCE_MILLS = 100L
    }
}
