package org.thoughtcrime.securesms.home

import android.content.ContentResolver
import android.content.Context
import androidx.annotation.AttrRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.squareup.phrase.Phrase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import network.loki.messenger.R
import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_HIDDEN
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.database.DatabaseContentProviders
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.sskenvironment.TypingStatusRepository
import org.thoughtcrime.securesms.util.observeChanges
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext as ApplicationContextQualifier

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val threadDb: ThreadDatabase,
    private val contentResolver: ContentResolver,
    private val prefs: TextSecurePreferences,
    @ApplicationContextQualifier private val context: Context,
    private val typingStatusRepository: TypingStatusRepository,
    private val configFactory: ConfigFactory
) : ViewModel() {
    // SharedFlow that emits whenever the user asks us to reload  the conversation
    private val manualReloadTrigger = MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val overrideMessageSnippets = MutableStateFlow(emptyMap<Long, MessageSnippetOverride>())

    /**
     * A [StateFlow] that emits the list of threads and the typing status of each thread.
     *
     * This flow will emit whenever the user asks us to reload the conversation list or
     * whenever the conversation list changes.
     */
    val data: StateFlow<Data?> = combine(
        observeConversationList(),
        observeTypingStatus(),
        overrideMessageSnippets,
        messageRequests(),
        hasHiddenNoteToSelf()
    ) { threads, typingStatus, overrideMessageSnippets, messageRequests, hideNoteToSelf ->
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
                        overriddenSnippet = overrideMessageSnippets[thread.threadId]
                    )
                }
            }
        )
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
    private fun reloadTriggersAndContentChanges() = merge(
        manualReloadTrigger,
        contentResolver.observeChanges(DatabaseContentProviders.ConversationList.CONTENT_URI)
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
            val overriddenSnippet: MessageSnippetOverride?
        ) : Item

        data class MessageRequests(val count: Int) : Item
    }

    private fun createMessageRequests(
        count: Int,
        hidden: Boolean,
    ) = if (count > 0 && !hidden) Item.MessageRequests(count) else null

    fun onLeavingGroupStarted(threadId: Long) {
        val message = MessageSnippetOverride(
            text = context.getString(R.string.leaving),
            colorAttr = android.R.attr.textColorTertiary
        )

        overrideMessageSnippets.update { it + (threadId to message) }
    }

    fun onLeavingGroupFinished(threadId: Long, isError: Boolean) {
        if (isError) {
            val errorMessage = MessageSnippetOverride(
                text = Phrase.from(context, R.string.groupLeaveErrorFailed)
                    .put(GROUP_NAME_KEY,
                        data.value?.items
                            ?.asSequence()
                            ?.filterIsInstance<Item.Thread>()
                            ?.find { it.thread.threadId == threadId }
                            ?.thread?.recipient?.name
                            ?: context.getString(R.string.unknown)
                    )
                    .format(),
                colorAttr = R.attr.danger
            )

            overrideMessageSnippets.update { it + (threadId to errorMessage) }
        } else {
            overrideMessageSnippets.update { it - threadId }
        }
    }

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
