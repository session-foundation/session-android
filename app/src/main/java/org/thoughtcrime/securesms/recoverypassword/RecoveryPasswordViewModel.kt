package org.thoughtcrime.securesms.recoverypassword

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.crypto.MnemonicCodec
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.crypto.MnemonicUtilities
import javax.inject.Inject

@HiltViewModel
class RecoveryPasswordViewModel @Inject constructor(
    private val application: Application,
    private val prefs: TextSecurePreferences,
    private val loginStateRepository: LoginStateRepository,
): AndroidViewModel(application) {

    val seed: StateFlow<String?> = loginStateRepository
        .loggedInState
        .map { it?.seeded?.seed?.data?.toHexString() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val mnemonic = seed.filterNotNull()
        .map {
            MnemonicCodec {
                MnemonicUtilities.loadFileContents(application, it)
            }
            .encode(it, MnemonicCodec.Language.Configuration.english)
            .trim() // Remove any leading or trailing whitespace
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun copyMnemonic() {
        prefs.setHasViewedSeed(true)

        // Ensure that our mnemonic words are separated by single spaces only without any excessive
        // whitespace or control characters via:
        //   - Replacing all control chars (\p{Cc}) or Unicode separators (\p{Z}) with a single space, then
        //   - Trimming all leading & trailing spaces.
        val normalisedMnemonic = mnemonic.value
            .replace(Regex("[\\p{Cc}\\p{Z}]+"), " ")
            .trim()

        ClipData.newPlainText("Seed", normalisedMnemonic)
            .let(application.clipboard::setPrimaryClip)
    }
}

private val Context.clipboard get() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
