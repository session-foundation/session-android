package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.PushRegistrationDatabase
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
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
    private val preferences: TextSecurePreferences,
    private val tokenFetcher: TokenFetcher,
    @param:ApplicationContext private val context: Context,
    @param:ManagerScope private val scope: CoroutineScope,
    @param:PushNotificationModule.PushProcessingSemaphore
    private val semaphore: Semaphore,
    private val pushRegistrationDatabase: PushRegistrationDatabase,
    private val loginStateRepository: LoginStateRepository,
) : OnAppStartupComponent {

    private var job: Job? = null

    @OptIn(FlowPreview::class)
    override fun onPostAppStarted() {
        require(job == null) { "Job is already running" }


        job = scope.launch {
            val firstRun = AtomicBoolean(true)

            loginStateRepository
                .flowWithLoggedInState {
                    combine(
                        configFactory.userConfigsChanged(
                            onlyConfigTypes = EnumSet.of(UserConfigType.USER_GROUPS),
                            debounceMills = 500
                        )
                            .castAwayType()
                            .onStart { emit(Unit) },
                        preferences.pushEnabled,
                        tokenFetcher.token.filterNotNull().filter { !it.isBlank() }
                    ) { _, enabled, token ->
                        if (enabled) {
                            desiredSubscriptions(loginStateRepository.requireLocalNumber(), token)
                        } else {
                            emptyList()
                        }
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
