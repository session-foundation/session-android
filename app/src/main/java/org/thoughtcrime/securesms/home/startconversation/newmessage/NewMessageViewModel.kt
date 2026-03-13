package org.thoughtcrime.securesms.home.startconversation.newmessage

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.squareup.phrase.Phrase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.OpenGroupUrlParser
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.error.UnhandledStatusCodeException
import org.thoughtcrime.securesms.ui.GetString
import java.net.IDN
import javax.inject.Inject

@HiltViewModel(assistedFactory = NewMessageViewModel.Factory::class)
class NewMessageViewModel @AssistedInject constructor(
    @Assisted private val allowCommunityUrl: Boolean,
    private val application: Application,
    private val onsResolver: OnsResolver,
) : ViewModel(), Callbacks {
    @AssistedFactory
    interface Factory {
        fun create(allowCommunityUrl: Boolean): NewMessageViewModel
    }

    private val HELP_URL : String = "https://getsession.org/account-ids"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _success = MutableSharedFlow<Success>()
    val success get() = _success

    private val _qrErrors = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val qrErrors: SharedFlow<String> = _qrErrors.asSharedFlow()

    private var loadOnsJob: Job? = null

    private var lasQrScan: Long = 0L
    private val qrDebounceTime = 3000L

    override fun onChange(value: String) {
        loadOnsJob?.cancel()
        loadOnsJob = null
        _state.update {
            it.copy(
                newMessageIdOrOns = value,
                isTextErrorColor = false,
                loading = false
            )
        }
    }

    override fun onContinue() {
        val trimmed = state.value.newMessageIdOrOns.trim()
        // Check if all characters are ASCII (code <= 127).
        val idOrONS = if (trimmed.all { it.code <= 127 }) {
            // Already ASCII (or punycode‐ready); no conversion needed.
            trimmed
        } else {
            try {
                // For non-ASCII input (e.g. with emojis), attempt to puny-encode
                IDN.toASCII(trimmed, IDN.ALLOW_UNASSIGNED)
            } catch (e: IllegalArgumentException) {
                // if the above failed, resort to the original trimmed string
                Log.w("", "IDN.toASCII failed. Returning: $trimmed")
                trimmed
            }
        }

        // check if we have a community URL
        val isCommunityUrl: Boolean = if(allowCommunityUrl) {
            try {
                OpenGroupUrlParser.parseUrl(idOrONS)
                true
            } catch (_: OpenGroupUrlParser.Error) {
                false
            }
        } else {
            false
        }

        if (isCommunityUrl) {
            onCommunityUrlEntered(idOrONS)
        } else if (AccountId.hasValidLength(idOrONS)) {
            if (isValidStandardAddress(idOrONS)) {
                onPublicKey(idOrONS)
            } else {
                _state.update {
                    it.copy(
                        isTextErrorColor = true,
                        error = GetString(R.string.accountIdErrorInvalid),
                        loading = false
                    )
                }
            }
        } else {
            resolveONS(idOrONS)
        }
    }

    override fun onScanQrCode(value: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lasQrScan > qrDebounceTime) {
            lasQrScan = currentTime
            if (isValidStandardAddress(value)) {
                onChange(value)
                _state.update { it.copy(validIdFromQr = value) }
            } else {
                _qrErrors.tryEmit(application.getString(R.string.qrNotAccountId))
                _state.update { it.copy(validIdFromQr = "") }
            }
        }
    }

    private fun isValidStandardAddress(address: String): Boolean =
        AccountId.fromStringOrNull(address)?.prefix == IdPrefix.STANDARD

    override fun onClearQrCode() {
        _state.update {it.copy(validIdFromQr = "") }
    }

    private fun resolveONS(ons: String) {
        if (loadOnsJob?.isActive == true) return

        // This could be an ONS name
        _state.update { it.copy(isTextErrorColor = false, error = null, loading = true) }

        loadOnsJob = viewModelScope.launch {
            try {
                val publicKey = requireNotNull(
                    withTimeoutOrNull(30_000L, {
                        onsResolver.resolve(ons)
                    })) {
                    "Timeout waiting for ONS resolution"
                }
                onPublicKey(publicKey)
            } catch (e: Exception) {
                Log.w("", "Error resolving ONS:", e)
                onError(e)
            }
        }
    }

    private fun onError(e: Exception) {
        _state.update {
            it.copy(
                loading = false,
                isTextErrorColor = true,
                error = GetString(e) { it.toMessage() })
        }
    }

    private fun onCommunityUrlEntered(url: String){
        _state.update { it.copy(loading = false) }


    }

    private fun onCommunityUrlScanned(url: String){

    }

    private fun openOrJoinCommunity(url: String){

    }

    private fun onPublicKey(publicKey: String) {
        _state.update { it.copy(loading = false) }

        val address = publicKey.toAddress()
        if (address is Address.Standard) {
            viewModelScope.launch { _success.emit(Success.NewMessage(address)) }
        }
    }

    private fun Exception.toMessage() = when (this) {
        is UnhandledStatusCodeException -> application.getString(R.string.errorUnregisteredOns)
        else -> Phrase.from(application, R.string.errorNoLookupOns)
            .put(APP_NAME_KEY, application.getString(R.string.app_name))
            .format().toString()
    }

    fun onCommand(commands: Commands) {
        when (commands) {
            is Commands.ShowUrlDialog -> {
                _state.update { it.copy(showUrlDialog = HELP_URL) }
            }

            is Commands.DismissUrlDialog -> {
                _state.update {
                    it.copy(
                        showUrlDialog = null
                    )
                }
            }
        }
    }

    sealed interface Commands {
        data object ShowUrlDialog : Commands
        data object DismissUrlDialog : Commands
    }
}

data class State(
    val newMessageIdOrOns: String = "",
    val isTextErrorColor: Boolean = false,
    val error: GetString? = null,
    val loading: Boolean = false,
    val showUrlDialog: String? = null,
    val validIdFromQr: String = "",
) {
    val isNextButtonEnabled: Boolean get() = newMessageIdOrOns.isNotBlank()
}


sealed class Success{
    data class NewMessage(val address: Address.Standard): Success()
    data class Community(val address: Address.Standard): Success()
}