package org.thoughtcrime.securesms.crypto

import kotlinx.serialization.json.Json
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Util
import org.thoughtcrime.securesms.preferences.PreferenceKey
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton


/**
 * A provider that is responsible for creating or retrieving the AttachmentSecret model.
 * 
 * On modern Android, the serialized secrets are themselves encrypted using a key that lives
 * in the system KeyStore, for whatever that is worth.
 */
@Singleton
class AttachmentSecretProvider @Inject constructor(
    private val json: Json,
    private val prefs: PreferenceStorage,
) {
    private var attachmentSecret: AttachmentSecret? = null


    @Synchronized
    fun getOrCreateAttachmentSecret(): AttachmentSecret {
        if (attachmentSecret != null) return attachmentSecret!!

        val secret = runCatching { prefs[unencryptedKey]?.let(this::migrateUnencryptedKey) }.getOrNull()
            ?: runCatching { prefs[encryptedKey]?.let(this::getEncryptedAttachmentSecret) }
                .onFailure {
                    Log.w("AttachmentSecretProvider", "Failed to read encrypted attachment secret, will create a new one", it)
                }
                .getOrNull()
            ?: createAndStoreAttachmentSecret()

        attachmentSecret = secret
        return secret
    }

    private fun migrateUnencryptedKey(
        unencryptedSecret: AttachmentSecret
    ): AttachmentSecret {
        val encryptedSecret = KeyStoreHelper.seal(json.encodeToString(unencryptedSecret).toByteArray())
        prefs[encryptedKey] = encryptedSecret
        prefs.remove(unencryptedKey)
        return unencryptedSecret
    }

    private fun getEncryptedAttachmentSecret(encryptedSecret: SealedData): AttachmentSecret {
        return json.decodeFromString(KeyStoreHelper.unseal(encryptedSecret).decodeToString())
    }

    private fun createAndStoreAttachmentSecret(): AttachmentSecret {
        val secret = ByteArray(32)
        Util.SECURE_RANDOM.nextBytes(secret)

        val attachmentSecret = AttachmentSecret(
            modernKey = secret
        )

        prefs[encryptedKey] = KeyStoreHelper.seal(json.encodeToString(attachmentSecret).toByteArray())
        return attachmentSecret
    }


    companion object {
        private val encryptedKey = PreferenceKey.json<SealedData>("pref_attachment_encrypted_secret")
        private val unencryptedKey = PreferenceKey.json<AttachmentSecret>("pref_attachment_unencrypted_secret")
    }

}
