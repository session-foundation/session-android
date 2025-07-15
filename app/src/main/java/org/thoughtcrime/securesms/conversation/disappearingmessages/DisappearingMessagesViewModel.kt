package org.thoughtcrime.securesms.conversation.disappearingmessages

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.conversation.disappearingmessages.ui.UiState
import org.thoughtcrime.securesms.conversation.disappearingmessages.ui.toUiState
import org.thoughtcrime.securesms.conversation.v2.settings.ConversationSettingsNavigator
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.Storage

@HiltViewModel(assistedFactory = DisappearingMessagesViewModel.Factory::class)
class DisappearingMessagesViewModel @AssistedInject constructor(
    @Assisted private val address: Address,
    @Assisted("isNewConfigEnabled")  private val isNewConfigEnabled: Boolean,
    @Assisted("showDebugOptions")    private val showDebugOptions: Boolean,
    @param:ApplicationContext private val context: Context,
    private val textSecurePreferences: TextSecurePreferences,
    private val disappearingMessages: DisappearingMessages,
    private val groupDb: GroupDatabase,
    private val storage: Storage,
    private val navigator: ConversationSettingsNavigator,
    private val recipientRepository: RecipientRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        State(
            isNewConfigEnabled = isNewConfigEnabled,
            showDebugOptions = showDebugOptions
        )
    )
    val state = _state.asStateFlow()

    val uiState = _state
        .map(State::toUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch {
            val expiryMode = recipientRepository.getRecipientOrEmpty(address).expiryMode

            val isAdmin = when {
                address.isGroupV2 -> {
                    // Handle the new closed group functionality
                    storage.getMembers(address.toString()).any { it.accountId() == textSecurePreferences.getLocalNumber() && it.admin }
                }

                address.isLegacyGroup -> {
                    val groupRecord = groupDb.getGroup(address.toGroupString()).orNull()
                    // Handle as legacy group
                    groupRecord?.admins?.any{ it.toString() == textSecurePreferences.getLocalNumber() } == true
                }
                else -> !address.isGroupOrCommunity
            }

            _state.update {
                it.copy(
                    address = address,
                    isGroup = address.isGroup,
                    isNoteToSelf = address.toString() == textSecurePreferences.getLocalNumber(),
                    isSelfAdmin = isAdmin,
                    expiryMode = expiryMode,
                    persistedMode = expiryMode
                )
            }
        }
    }

    fun onOptionSelected(value: ExpiryMode) = _state.update { it.copy(expiryMode = value) }

    fun onSetClicked() = viewModelScope.launch {
        val state = _state.value
        val mode = state.expiryMode
        val address = state.address
        if (address == null || mode == null) {
            Toast.makeText(
                context, context.getString(R.string.communityErrorDescription), Toast.LENGTH_SHORT
            ).show()
            return@launch
        }

        disappearingMessages.set(address, mode, state.isGroup)

        navigator.navigateUp()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            address: Address,
            @Assisted("isNewConfigEnabled") isNewConfigEnabled: Boolean,
            @Assisted("showDebugOptions")   showDebugOptions: Boolean
        ): DisappearingMessagesViewModel
    }
}
