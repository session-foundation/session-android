package org.thoughtcrime.securesms.onboarding.manager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.PRIORITY_HIDDEN
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.withMutableUserConfigs
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.ReceivedMessageHashDatabase
import javax.inject.Inject

class CreateAccountManager @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val receivedMessageHashDatabase: ReceivedMessageHashDatabase,
    private val loginStateRepository: LoginStateRepository,
    private val database: LokiAPIDatabaseProtocol,
) {
    suspend fun createAccount(displayName: String) {
        withContext(Dispatchers.Default) {
            // This is here to resolve a case where the app restarts before a user completes onboarding
            // which can result in an invalid database state
            database.clearAllLastMessageHashes()
            receivedMessageHashDatabase.removeAll()

            loginStateRepository.update { oldState ->
                if (oldState != null) {
                    Log.wtf("CreateAccountManager", "Tried to create account when already logged in!")
                    return@update oldState
                }

                LoggedInState.generate(seed = null)
            }

            configFactory.withMutableUserConfigs {
                it.userProfile.setName(displayName)
                it.userProfile.setNtsPriority(PRIORITY_HIDDEN)
            }
        }
    }
}