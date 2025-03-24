package org.thoughtcrime.securesms.home

import android.content.ContentResolver
import android.content.Context
import androidx.annotation.AttrRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.DatabaseContentProviders
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.sskenvironment.TypingStatusRepository
import org.thoughtcrime.securesms.util.observeChanges
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val threadDb: ThreadDatabase,
    private val contentResolver: ContentResolver,
    private val prefs: TextSecurePreferences,
    private val typingStatusRepository: TypingStatusRepository,
    private val configFactory: ConfigFactory
) : ViewModel() {
    // SharedFlow that emits whenever the user asks us to reload  the conversation
    private val manualReloadTrigger = MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

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
                    // if the note to self is marked as hidden, do not add it
                    if (thread.recipient.isLocalNumber && hideNoteToSelf) {
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
        hidden: Boolean,
    ) = if (count > 0 && !hidden) Item.MessageRequests(count) else null


    fun hideNoteToSelf() {
        prefs.setHasHiddenNoteToSelf(true)
        configFactory.withMutableUserConfigs {
            it.userProfile.setNtsPriority(PRIORITY_HIDDEN)
        }
    }

    companion object {
        private const val CHANGE_NOTIFICATION_DEBOUNCE_MILLS = 100L
    }
}
