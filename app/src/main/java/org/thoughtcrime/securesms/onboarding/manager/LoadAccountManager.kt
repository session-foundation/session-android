package org.thoughtcrime.securesms.onboarding.manager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.ReceivedMessageHashDatabase
import org.thoughtcrime.securesms.onboarding.OnBoardingPreferences.HAS_VIEWED_SEED
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import javax.inject.Inject

class LoadAccountManager @Inject constructor(
    private val prefs: PreferenceStorage,
    private val receivedMessageHashDatabase: ReceivedMessageHashDatabase,
    private val loginStateRepository: LoginStateRepository,
    private val database: LokiAPIDatabaseProtocol
) {

    suspend fun load(seed: ByteArray) {
        withContext(Dispatchers.Default) {
            // This is here to resolve a case where the app restarts before a user completes onboarding
            // which can result in an invalid database state
            database.clearAllLastMessageHashes()
            receivedMessageHashDatabase.removeAll()

            loginStateRepository.update { old ->
                if (old != null) {
                    Log.wtf("LoadAccountManager", "Tried to load account when already logged in!")
                    return@update old
                }

                LoggedInState.generate(seed)
            }

            // Mark that the user has viewed their seed to prevent being prompted again
            prefs[HAS_VIEWED_SEED] = true
        }
    }
}
