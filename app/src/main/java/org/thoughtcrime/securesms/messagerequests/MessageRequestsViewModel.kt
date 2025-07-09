package org.thoughtcrime.securesms.messagerequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.repository.ConversationRepository
import javax.inject.Inject

@HiltViewModel
class MessageRequestsViewModel @Inject constructor(
    private val repository: ConversationRepository,
) : ViewModel() {
    private val reloadTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @OptIn(ExperimentalCoroutinesApi::class)
    val threads: StateFlow<List<ThreadRecord>> = reloadTrigger
        .onStart { emit(Unit) }
        .flatMapLatest {
            repository.observeConversationList(approved = false)
                .map { it.sortedWith(COMPARATOR) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())


    // We assume thread.recipient is a contact or thread.invitingAdmin is not null
    fun blockMessageRequest(thread: ThreadRecord, blockRecipient: Address) = viewModelScope.launch {
        repository.setBlocked(blockRecipient, true)
        deleteMessageRequest(thread)
        reloadTrigger.emit(Unit)
    }

    fun deleteMessageRequest(thread: ThreadRecord) = viewModelScope.launch {
        repository.deleteMessageRequest(thread)
        reloadTrigger.emit(Unit)
    }

    fun clearAllMessageRequests(block: Boolean) = viewModelScope.launch {
        repository.clearAllMessageRequests(block)
        reloadTrigger.emit(Unit)
    }

    companion object {
        private val COMPARATOR get() = compareByDescending<ThreadRecord> { it.lastMessage?.timestamp ?: 0 }
            .thenByDescending { it.date }
            .thenBy { it.recipient.displayName }
    }
}
