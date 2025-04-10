package org.thoughtcrime.securesms.home.search

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.DatabaseContentProviders
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.search.SearchRepository
import org.thoughtcrime.securesms.search.model.SearchResult
import org.thoughtcrime.securesms.util.observeChanges
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val application: Application,
    private val searchRepository: SearchRepository,
    private val configFactory: ConfigFactory,
) : ViewModel() {

    // The query text here is not the source of truth due to the limitation of Android view system
    // Currently it's only set by the user input: if you try to set it programmatically, it won't
    // be reflected in the UI and could be overwritten by the user input.
    private val _queryText = MutableStateFlow<String>("")

    private fun observeChangesAffectingSearch(): Flow<*> = merge(
        application.contentResolver.observeChanges(DatabaseContentProviders.ConversationList.CONTENT_URI),
        configFactory.configUpdateNotifications
    )

    val noteToSelfString by lazy { application.getString(R.string.noteToSelf).lowercase() }

    val result = combine(
        _queryText,
        observeChangesAffectingSearch().onStart { emit(Unit) }
    ) { query, _ -> query }
        .debounce(300L)
        .mapLatest { query ->
            try {
                if (query.isBlank()) {
                    withContext(Dispatchers.Default) {
                        // searching for 05 as contactDb#getAllContacts was not returning contacts
                        // without a nickname/name who haven't approved us.
                        GlobalSearchResult(
                            query,
                            searchRepository.queryContacts("05").first.toList()
                        )
                    }
                } else {
                    val results = searchRepository.suspendQuery(query).toGlobalSearchResult()

                    // show "Note to Self" is the user searches for parts of"Note to Self"
                    if(noteToSelfString.contains(query.lowercase())){
                        results.copy(showNoteToSelf = true)
                    } else {
                        results
                    }
                }
            } catch (e: Exception) {
                Log.e("GlobalSearchViewModel", "Error searching len = ${query.length}", e)
                GlobalSearchResult(query)
            }
        }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), 0)

    fun setQuery(charSequence: CharSequence) {
        _queryText.value = charSequence.toString()
    }
}

private suspend fun SearchRepository.suspendQuery(query: String): SearchResult {
    return suspendCoroutine { cont ->
        query(query, cont::resume)
    }
}
