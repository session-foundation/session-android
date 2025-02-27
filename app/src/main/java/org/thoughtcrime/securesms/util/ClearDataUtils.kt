package org.thoughtcrime.securesms.util

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.home.HomeActivity
import javax.inject.Inject
import androidx.core.content.edit
import kotlinx.coroutines.tasks.await

class ClearDataUtils @Inject constructor(
    private val application: Application,
    private val configFactory: ConfigFactory,
) {
    // Method to clear the local data - returns true on success otherwise false
    @SuppressLint("ApplySharedPref")
    suspend fun clearAllData() {
        return withContext(Dispatchers.Default) {
            // Should not proceed if we can't delete db
            check(application.deleteDatabase(SQLCipherOpenHelper.DATABASE_NAME)) {
                "Failed to delete database"
            }

            TextSecurePreferences.clearAll(application)
            application.getSharedPreferences(ApplicationContext.PREFERENCES_NAME, 0).edit(commit = true) { clear() }
            configFactory.clearAll()

            // The token deletion is nice but not critical, so don't let it block the rest of the process
            runCatching {
                FirebaseMessaging.getInstance().deleteToken().await()
            }.onFailure { e ->
                Log.w("ClearDataUtils", "Failed to delete Firebase token: ${e.message}", e)
            }
        }
    }

    /**
     * Clear all local profile data and message history then restart the app after a brief delay.
     * @return true on success, false otherwise.
     */
    @SuppressLint("ApplySharedPref")
    suspend fun clearAllDataAndRestart() {
        clearAllData()
        delay(200)
        restartApplication()
    }

    fun restartApplication() {
        val intent = Intent(application, HomeActivity::class.java)
        application.startActivity(Intent.makeRestartActivityTask(intent.component))
        Runtime.getRuntime().exit(0)
    }

}