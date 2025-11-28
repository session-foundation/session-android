package org.thoughtcrime.securesms.onboarding.manager

import android.app.Application
import network.loki.messenger.libsession_util.PRIORITY_HIDDEN
import org.session.libsession.snode.SnodeModule
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.ReceivedMessageHashDatabase
import org.thoughtcrime.securesms.util.VersionDataFetcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CreateAccountManager @Inject constructor(
    private val versionDataFetcher: VersionDataFetcher,
    private val configFactory: ConfigFactoryProtocol,
    private val receivedMessageHashDatabase: ReceivedMessageHashDatabase,
    private val loginStateRepository: LoginStateRepository,
) {
    private val database: LokiAPIDatabaseProtocol
        get() = SnodeModule.shared.storage

    fun createAccount(displayName: String) {
        // This is here to resolve a case where the app restarts before a user completes onboarding
        // which can result in an invalid database state
        database.clearAllLastMessageHashes()
        receivedMessageHashDatabase.removeAll()

        loginStateRepository.update { oldState ->
            require(oldState == null) {
                "Attempting to create a new account when one already exists!"
            }

            LoggedInState.generate(seed = null)
        }

        configFactory.withMutableUserConfigs {
            it.userProfile.setName(displayName)
            it.userProfile.setNtsPriority(PRIORITY_HIDDEN)
        }

        versionDataFetcher.startTimedVersionCheck()
    }
}