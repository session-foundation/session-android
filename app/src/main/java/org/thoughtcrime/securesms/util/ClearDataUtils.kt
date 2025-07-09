package org.thoughtcrime.securesms.util

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.decodeHex
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.hexEncodedPublicKey
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.crypto.KeyPairUtilities
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.glide.CommunityFileDownloadWorker
import org.thoughtcrime.securesms.glide.EncryptedFileDownloadWorker
import org.thoughtcrime.securesms.home.HomeActivity
import org.thoughtcrime.securesms.logging.PersistentLogger
import org.thoughtcrime.securesms.migration.DatabaseMigrationManager
import javax.inject.Inject

class ClearDataUtils @Inject constructor(
    private val application: Application,
    private val configFactory: ConfigFactory,
    private val tokenFetcher: TokenFetcher,
    private val storage: Storage,
    private val prefs: TextSecurePreferences,
    private val persistentLogger: PersistentLogger,
) {
    // Method to clear the local data - returns true on success otherwise false
    @SuppressLint("ApplySharedPref")
    suspend fun clearAllData(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        return withContext(dispatcher) {
            // Should not proceed if there's a db but we can't delete it
            check(
                !application.getDatabasePath(SQLCipherOpenHelper.DATABASE_NAME).exists() ||
                application.deleteDatabase(SQLCipherOpenHelper.DATABASE_NAME)
            ) {
                "Failed to delete database"
            }

            // Also delete the other legacy databases but don't care about the result
            application.deleteDatabase(DatabaseMigrationManager.CIPHER4_DB_NAME)
            application.deleteDatabase(DatabaseMigrationManager.CIPHER3_DB_NAME)

            TextSecurePreferences.clearAll(application)
            application.getSharedPreferences(ApplicationContext.PREFERENCES_NAME, 0).edit(commit = true) { clear() }
            application.cacheDir.deleteRecursively()
            application.filesDir.deleteRecursively()
            configFactory.clearAll()

            EncryptedFileDownloadWorker.cancelAll(application)
            CommunityFileDownloadWorker.cancelAll(application)

            persistentLogger.deleteAllLogs()

            // The token deletion is nice but not critical, so don't let it block the rest of the process
            runCatching {
                tokenFetcher.resetToken()
            }.onFailure { e ->
                Log.w("ClearDataUtils", "Failed to reset push notification token: ${e.message}", e)
            }
        }
    }

    suspend fun clearAllDataWithoutLoggingOutAndRestart(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        withContext(dispatcher) {
            val keyPair = storage.getUserED25519KeyPair()
            if (keyPair != null) {
                val x25519KeyPair = storage.getUserX25519KeyPair()
                val seed =
                    IdentityKeyUtil.retrieve(application, IdentityKeyUtil.LOKI_SEED).decodeHex()
                        .toByteArray()

                clearAllData(Dispatchers.Unconfined)
                KeyPairUtilities.store(application, seed, keyPair, x25519KeyPair)
                prefs.setLocalNumber(x25519KeyPair.hexEncodedPublicKey)
            } else {
                clearAllData(Dispatchers.Unconfined)
            }

            delay(200)
            restartApplication()
        }
    }

    /**
     * Clear all local profile data and message history then restart the app after a brief delay.
     * @return true on success, false otherwise.
     */
    @SuppressLint("ApplySharedPref")
    suspend fun clearAllDataAndRestart(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        withContext(dispatcher) {
            clearAllData(Dispatchers.Unconfined)
            delay(200)
            restartApplication()
        }
    }

    fun restartApplication() {
        val intent = Intent(application, HomeActivity::class.java)
        application.startActivity(Intent.makeRestartActivityTask(intent.component))
        Runtime.getRuntime().exit(0)
    }

}