package org.thoughtcrime.securesms.crypto

import kotlinx.serialization.json.Json
import org.session.libsignal.utilities.Util
import org.thoughtcrime.securesms.preferences.PreferenceKey
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseSecretProvider @Inject constructor(
    private val prefs: PreferenceStorage,
    private val json: Json,
) {
    companion object {
        private val keyUnencryptedSecret = PreferenceKey.string("pref_database_unencrypted_secret")
        private val keyEncryptedSecret = PreferenceKey.json<SealedData>("pref_database_encrypted_secret")
    }

    @Synchronized
    fun getOrCreateDatabaseSecret(): DatabaseSecret {
        prefs[keyUnencryptedSecret]?.let {
            return getUnencryptedDatabaseSecret(it)
        }

        prefs[keyEncryptedSecret]?.let {
            return getEncryptedDatabaseSecret(it)
        }

        return createAndStoreDatabaseSecret()
    }

    private fun getUnencryptedDatabaseSecret(
        unencryptedSecret: String
    ): DatabaseSecret {
        try {
            val databaseSecret = DatabaseSecret(unencryptedSecret)
            val encryptedSecret = KeyStoreHelper.seal(databaseSecret.asBytes())

            prefs[keyEncryptedSecret] = encryptedSecret
            prefs.remove(keyUnencryptedSecret)

            return databaseSecret
        } catch (e: IOException) {
            throw AssertionError(e)
        }
    }

    private fun getEncryptedDatabaseSecret(encryptedSecret: SealedData): DatabaseSecret {
        return DatabaseSecret(KeyStoreHelper.unseal(encryptedSecret))
    }

    private fun createAndStoreDatabaseSecret(): DatabaseSecret {
        val secret = ByteArray(32)
        Util.SECURE_RANDOM.nextBytes(secret)

        val databaseSecret = DatabaseSecret(secret)
        prefs[keyEncryptedSecret] = KeyStoreHelper.seal(databaseSecret.asBytes())
        return databaseSecret
    }
}