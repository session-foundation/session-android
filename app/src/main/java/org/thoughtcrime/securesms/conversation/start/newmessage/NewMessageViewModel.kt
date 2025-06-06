package org.thoughtcrime.securesms.conversation.start.newmessage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import network.loki.messenger.R
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.utilities.await
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.PublicKeyValidation
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.LoadingDialog
import java.net.IDN

@HiltViewModel
internal class NewMessageViewModel @Inject constructor(
    private val application: Application
): AndroidViewModel(application), Callbacks {

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val _success = MutableSharedFlow<Success>()
    val success get() = _success.asSharedFlow()

    private val _qrErrors = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val qrErrors = _qrErrors.asSharedFlow()

    private var loadOnsJob: Job? = null

    override fun onChange(value: String) {
        loadOnsJob?.cancel()
        loadOnsJob = null
        _state.update { it.copy(newMessageIdOrOns = value, isTextErrorColor = false, loading = false) }
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

        if (PublicKeyValidation.isValid(idOrONS, isPrefixRequired = false)) {
            onUnvalidatedPublicKey(publicKey = idOrONS)
        } else {
            resolveONS(ons = idOrONS)
        }
    }

    override fun onScanQrCode(value: String) {
        if (PublicKeyValidation.isValid(value, isPrefixRequired = false) && PublicKeyValidation.hasValidPrefix(value)) {
            onPublicKey(value)
        } else {
            _qrErrors.tryEmit(application.getString(R.string.qrNotAccountId))
        }
    }

    private fun resolveONS(ons: String) {
        if (loadOnsJob?.isActive == true) return

        // This could be an ONS name
        _state.update { it.copy(isTextErrorColor = false, error = null, loading = true) }

        loadOnsJob = viewModelScope.launch {
            try {
                val publicKey = withTimeout(30_000L, {
                    SnodeAPI.getAccountID(ons).await()
                })
                onPublicKey(publicKey)
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    private fun onError(e: Exception) {
        _state.update { it.copy(loading = false, isTextErrorColor = true, error = GetString(e) { it.toMessage() }) }
    }

    private fun onPublicKey(publicKey: String) {
        _state.update { it.copy(loading = false) }
        viewModelScope.launch { _success.emit(Success(publicKey)) }
    }

    private fun onUnvalidatedPublicKey(publicKey: String) {
        if (PublicKeyValidation.hasValidPrefix(publicKey)) {
            onPublicKey(publicKey)
        } else {
            _state.update { it.copy(isTextErrorColor = true, error = GetString(R.string.accountIdErrorInvalid), loading = false) }
        }
    }

    private fun Exception.toMessage() = when (this) {
        is SnodeAPI.Error.Generic -> application.getString(R.string.onsErrorNotRecognized)
        else -> application.getString(R.string.onsErrorUnableToSearch)
    }
}

internal data class State(
    val newMessageIdOrOns: String = "",
    val isTextErrorColor: Boolean = false,
    val error: GetString? = null,
    val loading: Boolean = false
) {
    val isNextButtonEnabled: Boolean get() = newMessageIdOrOns.isNotBlank()
}

internal data class Success(val publicKey: String)