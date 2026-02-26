package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.database.PushRegistrationDatabase
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.preferences.CommunicationPreferenceKeys
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.util.castAwayType
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A PN registration handler that watches for changes in the configs, and arrange the desired state
 * of push registrations into the database, and triggers [PushRegistrationWorker] to process them.
 */
@Singleton
class PushRegistrationHandler @Inject constructor(
    private val configFactory: ConfigFactory,
    private val preferences: PreferenceStorage,
    private val tokenFetcher: TokenFetcher,
    @param:ApplicationContext private val context: Context,
    @param:PushNotificationModule.PushProcessingSemaphore
    private val semaphore: Semaphore,
    private val pushRegistrationDatabase: PushRegistrationDatabase,
) : AuthAwareComponent {

    private val firstRun = AtomicBoolean(true)

    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState) {
        combine(
            configFactory.userConfigsChanged(
                onlyConfigTypes = EnumSet.of(UserConfigType.USER_GROUPS),
                debounceMills = 500
            )
                .castAwayType()
                .onStart { emit(Unit) },
            preferences.changes()
                .filter { it.name == CommunicationPreferenceKeys.PUSH_ENABLED.name }
                .onStart { emit(CommunicationPreferenceKeys.PUSH_ENABLED) }
                .map { preferences[CommunicationPreferenceKeys.PUSH_ENABLED] },
            tokenFetcher.token.filterNotNull().filter { !it.isBlank() }
        ) { _, enabled, token ->
            if (enabled) {
                desiredSubscriptions(loggedInState.accountId.hexString, token)
            } else {
                emptyList()
            }
        }
            .distinctUntilChanged()
            .collectLatest { desiredRegistrations ->
                val changes = semaphore.withPermit {
                    pushRegistrationDatabase.ensureRegistrations(desiredRegistrations)
                }

                Log.d(TAG, "Push registration changes: $changes")

                if (firstRun.compareAndSet(true, false) || changes > 0) {
                    PushRegistrationWorker.enqueue(context, delay = null).await()
                }
            }
    }

    /**
     * Build desired subscriptions: self (local number) + any group that shouldPoll.
     * */
    private fun desiredSubscriptions(localNumber: String, token: String): List<PushRegistrationDatabase.Registration> =
        buildList {
            val input = PushRegistrationDatabase.Input(pushToken = token)

            add(PushRegistrationDatabase.Registration(accountId = localNumber, input = input))

            val groups = configFactory.withUserConfigs { it.userGroups.allClosedGroupInfo() }
            for (group in groups) {
                if (group.shouldPoll) {
                    add(
                        PushRegistrationDatabase.Registration(
                            accountId = group.groupAccountId,
                            input = input
                        )
                    )
                }
            }
        }

    companion object {
        private const val TAG = "PushRegistrationHandler"
    }
}
