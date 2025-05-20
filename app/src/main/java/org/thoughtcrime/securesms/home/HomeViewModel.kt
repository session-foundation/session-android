package org.thoughtcrime.securesms.home

import android.content.ContentResolver
import android.content.Context
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.squareup.phrase.Phrase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
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
import kotlinx.coroutines.flow.onErrorResume
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.session.libsession.utilities.UsernameUtils
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.database.DatabaseContentProviders
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.sskenvironment.TypingStatusRepository
import org.thoughtcrime.securesms.util.observeChanges
import org.thoughtcrime.securesms.webrtc.CallManager
import org.thoughtcrime.securesms.webrtc.data.State
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val threadDb: ThreadDatabase,
    private val contentResolver: ContentResolver,
    private val prefs: TextSecurePreferences,
    private val typingStatusRepository: TypingStatusRepository,
    private val configFactory: ConfigFactory,
    private val callManager: CallManager,
    private val usernameUtils: UsernameUtils,
    private val storage: StorageProtocol
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

    companion object {
        private const val CHANGE_NOTIFICATION_DEBOUNCE_MILLS = 100L
    }
}
