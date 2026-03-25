package org.thoughtcrime.securesms.home.search

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.CommunityUrlParser
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.links.LinkChecker
import org.thoughtcrime.securesms.links.LinkType
import org.thoughtcrime.securesms.search.SearchRepository
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val application: Application,
    private val searchRepository: SearchRepository,
    private val configFactory: ConfigFactory,
    private val threadDatabase: ThreadDatabase,
    private val linkChecker: LinkChecker,
    private val recipientRepository: RecipientRepository
) : ViewModel() {

    // The query text here is not the source of truth due to the limitation of Android view system
    // Currently it's only set by the user input: if you try to set it programmatically, it won't
    // be reflected in the UI and could be overwritten by the user input.
    private val _queryText = MutableStateFlow<String>("")

    private fun observeChangesAffectingSearch(): Flow<*> = merge(
        threadDatabase.changeNotification,
        configFactory.configUpdateNotifications
    )

    val noteToSelfString: String by lazy { application.getString(R.string.noteToSelf).lowercase() }

    private val _uiEvents = MutableSharedFlow<UiEvent>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val uiEvents: SharedFlow<UiEvent> = _uiEvents

    init {
        // Deriving UI events from query changes
        _queryText
            .debounce(300L)
            .distinctUntilChanged()
            .mapLatest { query ->
                try {
                    deriveUiEvents(query)
                } catch (exception: Exception) {
                    Log.e("GlobalSearchViewModel", "Error deriving UI events", exception)
                    emptyList()
                }
            }
            .onEach { events ->
                events.forEach(_uiEvents::tryEmit)
            }
            .launchIn(viewModelScope)
    }

    val result: SharedFlow<GlobalSearchResult> = combine(
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
                            searchRepository.queryContacts().toList()
                        )
                    }
                } else {
                    var results = searchRepository.query(query).toGlobalSearchResult()

                    // Special cases
                    // community URL detected
                    val communityUrl = linkChecker.check(query) as? LinkType.CommunityLink
                    if(communityUrl != null){
                        // if the community is joined, add it to the result,
                        // otherwise a dialog is handled by the query event flow
                        if(communityUrl.joined){
                            // community is already joined: add it to the result list
                            val communityInfo = CommunityUrlParser.parse(communityUrl.url)
                            results = results.copy(
                                threads = results.threads + recipientRepository.getRecipientSync(
                                    Address.Community(
                                        serverUrl = communityInfo.baseUrl,
                                        room = communityInfo.room
                                    )
                                )
                            )
                        }
                    }

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

    private suspend fun deriveUiEvents(query: String): List<UiEvent> = withContext(Dispatchers.Default) {
        if (query.isBlank()) {
            return@withContext emptyList()
        }

        buildList {
            val communityUrl = linkChecker.check(query) as? LinkType.CommunityLink
            if (communityUrl != null && !communityUrl.joined) {
                add(
                    UiEvent.ShowUrlDialog(
                        communityUrl.copy(displayType = LinkType.CommunityLink.DisplayType.SEARCH)
                    )
                )
            }

            val accountId = AccountId.fromStringOrNull(query)
            if (accountId != null &&
                accountId.prefix == IdPrefix.STANDARD &&
                !searchRepository.queryContacts(query).any { it.address.toString() == query }
            ) {
                add(UiEvent.ShowNewConversationDialog(Address.Standard(accountId)))
            }
        }
    }

    fun setQuery(charSequence: CharSequence) {
        _queryText.value = charSequence.toString()
    }

    sealed interface UiEvent {
        data class ShowUrlDialog(val linkType: LinkType) : UiEvent
        data class ShowNewConversationDialog(val address: Address.Conversable) : UiEvent
    }
}
