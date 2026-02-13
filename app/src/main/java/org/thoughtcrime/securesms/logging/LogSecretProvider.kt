package org.thoughtcrime.securesms.logging

import org.session.libsignal.utilities.Util.SECURE_RANDOM
import org.thoughtcrime.securesms.crypto.KeyStoreHelper
import org.thoughtcrime.securesms.crypto.SealedData
import org.thoughtcrime.securesms.preferences.PreferenceKey
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogSecretProvider @Inject constructor(
    private val prefs: PreferenceStorage
) {
    companion object {
        private val encryptedKey =
            PreferenceKey.json<SealedData>("pref_log_encrypted_secret")
        private val unencryptedKey = PreferenceKey.bytes("pref_log_unencrypted_secret")
    }

    @Synchronized
    fun getOrCreateAttachmentSecret(): ByteArray {
        prefs[unencryptedKey]?.let { return it }
        prefs[encryptedKey]?.let {
            return KeyStoreHelper.unseal(it)
        }

        val secret = ByteArray(32)
        SECURE_RANDOM.nextBytes(secret)

        prefs[encryptedKey] = KeyStoreHelper.seal(secret)

        return secret
    }
}