package org.thoughtcrime.securesms.home.startconversation.community

import android.content.Context
import android.webkit.URLUtil
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsession.messaging.open_groups.OfficialCommunityRepository
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.CommunityUrlParser
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.groups.OpenGroupManager
import org.thoughtcrime.securesms.home.startconversation.group.CreateGroupEvent
import org.thoughtcrime.securesms.links.LinkChecker
import org.thoughtcrime.securesms.links.LinkType
import org.thoughtcrime.securesms.ui.getSubbedString
import org.thoughtcrime.securesms.util.State
import javax.inject.Inject

@HiltViewModel
class JoinCommunityViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val openGroupManager: OpenGroupManager,
    private val officialCommunityRepository: OfficialCommunityRepository,
    private val linkChecker: LinkChecker,

    ): ViewModel() {

    private val _state = MutableStateFlow(JoinCommunityState(defaultCommunities = State.Loading))
    val state: StateFlow<JoinCommunityState> = _state

    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val uiEvents: SharedFlow<UiEvent> get() = _uiEvents

    private var lasQrScan: Long = 0L
    private val qrDebounceTime = 3000L

    init {
        viewModelScope.launch {
            val groups = try {
                officialCommunityRepository.fetchOfficialCommunities()
            }
            catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("JoinCommunityViewModel", "Couldn't fetch official communities.", e)
                _state.update { it.copy(defaultCommunities = State.Error(e)) }
                return@launch
            }

            _state.update {
                it.copy(loading = false, defaultCommunities = State.Success(groups))
            }
        }
    }

    private fun joinCommunityIfPossible(url: String) {
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(loading = true) }

            val communityInfo = try {
                CommunityUrlParser.parse(url)
            } catch (e: CommunityUrlParser.Error) {
                _state.update { it.copy(loading = false) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        appContext,
                        appContext.getString(
                            when (e) {
                                CommunityUrlParser.Error.InvalidUrl -> R.string.communityJoinError
                                CommunityUrlParser.Error.InvalidPublicKey -> R.string.communityEnterUrlErrorInvalidDescription
                            }
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            // Check if we've already joined this community
            val communityLink = linkChecker.check(url) as? LinkType.CommunityLink

            if (communityLink?.joined == true) {
                _state.update { it.copy(urlDialog = communityLink.copy(allowCopyUrl = false)) }
                return@launch
            }

            try {
                openGroupManager.add(
                    server = communityInfo.baseUrl,
                    room = communityInfo.room,
                    publicKey = communityInfo.pubKeyHex,
                )

                _uiEvents.emit(UiEvent.NavigateToConversation(
                    address = Address.Community(communityInfo.baseUrl, communityInfo.room),
                ))
            } catch (e: Exception) {
                Log.e("Loki", "Couldn't join community.", e)
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(loading = false) }

                    val txt = appContext.getSubbedString(R.string.groupErrorJoin,
                        GROUP_NAME_KEY to url)
                    Toast.makeText(appContext, txt, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.OnQRScanned -> {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lasQrScan > qrDebounceTime) {
                    lasQrScan = currentTime
                    joinCommunityIfPossible(command.qr)
                }
            }

            is Commands.JoinCommunity -> {
                joinCommunityIfPossible(command.url)
            }

            is Commands.OnUrlChanged -> {
                _state.update {
                    it.copy(
                        communityUrl = command.url,
                        isJoinButtonEnabled = URLUtil.isValidUrl(command.url.trim())
                    )
                }
            }

            is Commands.OnDismissJoinedDialog -> {
                _state.update { it.copy(urlDialog = null) }
            }
        }
    }

    data class JoinCommunityState(
        val loading: Boolean = false,
        val isJoinButtonEnabled: Boolean = false,
        val communityUrl: String = "",
        val defaultCommunities: State<List<OpenGroupApi.DefaultGroup>>,
        val urlDialog: LinkType? = null
    )

    sealed interface Commands {
        data class OnQRScanned(val qr: String) : Commands
        data class JoinCommunity(val url: String): Commands
        data class OnUrlChanged(val url: String): Commands
        data object OnDismissJoinedDialog: Commands
    }

    sealed interface UiEvent {
        data class NavigateToConversation(val address: Address.Conversable) : UiEvent
    }
}
