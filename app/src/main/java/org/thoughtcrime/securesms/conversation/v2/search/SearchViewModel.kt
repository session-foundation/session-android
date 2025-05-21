package org.thoughtcrime.securesms.conversation.v2.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import javax.inject.Inject
import org.session.libsession.utilities.Debouncer
import org.session.libsession.utilities.Util.runOnMain
import org.thoughtcrime.securesms.database.CursorList
import org.thoughtcrime.securesms.search.SearchRepository
import org.thoughtcrime.securesms.search.model.MessageResult
import org.thoughtcrime.securesms.util.CloseableLiveData

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository
) : ViewModel() {

    companion object {
        const val MIN_QUERY_SIZE = 2
    }

    private val result: CloseableLiveData<SearchResult> = CloseableLiveData()
    private val debouncer: Debouncer = Debouncer(200)
    private var searchOpen = false
    private var activeThreadId: Long = 0
    val searchResults: LiveData<SearchResult>
        get() = result

    private val mutableSearchQuery: MutableStateFlow<String?> = MutableStateFlow(null)
    val searchQuery: StateFlow<String?> get() = mutableSearchQuery

    fun onQueryUpdated(query: String, threadId: Long) {
        if (query == mutableSearchQuery.value) {
            return
        }
        updateQuery(query, threadId)
    }

    fun onMissingResult() {
        if (mutableSearchQuery.value != null) {
            updateQuery(mutableSearchQuery.value!!, activeThreadId)
        }
    }

    fun onMoveUp() {
        debouncer.clear()
        val messages = result.value!!.getResults() as CursorList<MessageResult?>
        val position = Math.min(result.value!!.position + 1, messages.size - 1)
        result.setValue(SearchResult(messages, position), false)
    }

    fun onMoveDown() {
        debouncer.clear()
        val messages = result.value!!.getResults() as CursorList<MessageResult?>
        val position = Math.max(result.value!!.position - 1, 0)
        result.setValue(SearchResult(messages, position), false)
    }

    fun onSearchOpened() {
        searchOpen = true
    }

    fun onSearchClosed() {
        searchOpen = false
        mutableSearchQuery.value = null
        debouncer.clear()
        result.close()
    }

    override fun onCleared() {
        super.onCleared()
        result.close()
    }

    private fun updateQuery(query: String, threadId: Long) {
        mutableSearchQuery.value = query
        activeThreadId = threadId

        if(query.length < MIN_QUERY_SIZE) {
            result.value = SearchResult(CursorList.emptyList(), 0)
            return
        }

        debouncer.publish {
            searchRepository.query(query, threadId) { messages: CursorList<MessageResult?> ->
                runOnMain {
                    if (searchOpen && query == mutableSearchQuery.value) {
                        result.setValue(SearchResult(messages, 0))
                    } else {
                        messages.close()
                    }
                }
            }
        }
    }

    class SearchResult(
        private val results: CursorList<MessageResult?>,
        val position: Int
    ) : Closeable {

        fun getResults(): List<MessageResult?> {
            return results
        }

        override fun close() {
            results.close()
        }
    }

}