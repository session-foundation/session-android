package org.thoughtcrime.securesms.onboarding.manager

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.session.libsession.snode.SnodeModule
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.ReceivedMessageHashDatabase
import org.thoughtcrime.securesms.util.VersionDataFetcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LoadAccountManager @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val prefs: TextSecurePreferences,
    private val versionDataFetcher: VersionDataFetcher,
    private val receivedMessageHashDatabase: ReceivedMessageHashDatabase,
    private val loginStateRepository: LoginStateRepository,
) {
    private val database: LokiAPIDatabaseProtocol
        get() = SnodeModule.shared.storage

    private var restoreJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    fun load(seed: ByteArray) {
        // only have one sync job running at a time (prevent QR from trying to spawn a new job)
        if (restoreJob?.isActive == true) return

        restoreJob = scope.launch {
            // This is here to resolve a case where the app restarts before a user completes onboarding
            // which can result in an invalid database state
            database.clearAllLastMessageHashes()
            receivedMessageHashDatabase.removeAll()

            loginStateRepository.update {
                require(it == null) {
                    "Attempting to restore an account when one already exists!"
                }

                LoggedInState.generate(seed)
            }

            // Mark that the user has viewed their seed to prevent being prompted again
            prefs.setHasViewedSeed(true)

            versionDataFetcher.startTimedVersionCheck()
        }
    }
}
