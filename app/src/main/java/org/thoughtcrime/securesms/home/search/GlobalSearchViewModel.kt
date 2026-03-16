package org.thoughtcrime.securesms.home.search

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.OpenGroupUrlParser
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val application: Application,
    private val searchRepository: SearchRepository,
    private val configFactory: ConfigFactory,
    private val threadDatabase: ThreadDatabase,
    private val linkChecker: LinkChecker,
    recipientRepository: RecipientRepository
) : ViewModel() {

    // The query text here is not the source of truth due to the limitation of Android view system
    // Currently it's only set by the user input: if you try to set it programmatically, it won't
    // be reflected in the UI and could be overwritten by the user input.
    private val _queryText = MutableStateFlow<String>("")

    private fun observeChangesAffectingSearch(): Flow<*> = merge(
        threadDatabase.updateNotifications,
        configFactory.configUpdateNotifications
    )

    val noteToSelfString: String by lazy { application.getString(R.string.noteToSelf).lowercase() }

    private val _uiEvents = MutableSharedFlow<UiEvent>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val uiEvents: SharedFlow<UiEvent> = _uiEvents

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
                        // otherwise show a confirmation dialog
                        if(communityUrl.joined){
                            // community is already joined: add it to the result list
                            val openGroup = OpenGroupUrlParser.parseUrl(communityUrl.url)
                            results = results.copy(
                                threads = results.threads + recipientRepository.getRecipientSync(
                                    Address.Community(
                                        serverUrl = openGroup.server,
                                        room = openGroup.room
                                    )
                                )
                            )
                        } else {
                            // community not yet joined: show a confirmation dialog
                            _uiEvents.emit(UiEvent.ShowUrlDialog(communityUrl.copy(displayType = LinkType.CommunityLink.DisplayType.SEARCH)))
                        }
                    }

                    // Account ID detected, which is not a contact
                    val accountId = AccountId.fromStringOrNull(query)
                    val isStandardAccountId = accountId?.prefix == IdPrefix.STANDARD
                    if(isStandardAccountId && !results.contacts.any { it.address.toString() == query }){
                        _uiEvents.emit(UiEvent.ShowNewConversationDialog(Address.Standard(accountId)))
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

    fun setQuery(charSequence: CharSequence) {
        _queryText.value = charSequence.toString()
    }

    sealed interface UiEvent {
        data class ShowUrlDialog(val linkType: LinkType) : UiEvent
        data class ShowNewConversationDialog(val address: Address.Conversable) : UiEvent
    }
}

