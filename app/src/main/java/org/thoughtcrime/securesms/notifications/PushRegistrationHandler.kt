package org.thoughtcrime.securesms.notifications

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.PushRegistrationDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import org.thoughtcrime.securesms.util.castAwayType
import java.util.EnumSet
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
    private val storage: Storage,
    @param:ManagerScope private val scope: CoroutineScope,
    private val pushRegistrationDatabase: PushRegistrationDatabase,
) : OnAppStartupComponent {

    private var job: Job? = null

    @OptIn(FlowPreview::class)
    override fun onPostAppStarted() {
        require(job == null) { "Job is already running" }

        job = scope.launch {
            @Suppress("OPT_IN_USAGE")
            preferences.watchLocalNumber()
                .map { !it.isNullOrBlank() }
                .distinctUntilChanged()
                .flatMapLatest { isLoggedIn ->
                    if (isLoggedIn) {
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
                            if (enabled && hasCoreIdentity())
                                desiredSubscriptions(token)
                            else emptyList()
                        }
                    } else {
                        emptyFlow()
                    }
                }
                .distinctUntilChanged()
                .withIndex()
                .collect { (idx, registrations) ->
                    try {
                        reconcileWithDatabase(registrations, firstRun = idx == 0)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Reconciliation failed", t)
                    }
                }
        }
    }

    private suspend fun reconcileWithDatabase(
        registration: List<PushRegistrationDatabase.Registration>,
        firstRun: Boolean,
    ) {
        val numChanges = pushRegistrationDatabase.ensureRegistrations(registration)
        Log.d(TAG, "Reconciliation resulted in $numChanges registration changes")

        if (numChanges > 0 || firstRun) {
            // Try to cancel any non-running work to avoid redundant work piling up. But if
            // it's not possible, it doesn't hurt to have extra worker runs.
            PushRegistrationWorker.tryCancellingNonRunningWorks(context)

            // Make sure the worker is run immediately to handle any new registration change
            PushRegistrationWorker.enqueue(context, delay = null)
        }
    }

    /**
     * Build desired subscriptions: self (local number) + any group that shouldPoll.
     * */
    private fun desiredSubscriptions(token: String): List<PushRegistrationDatabase.Registration> =
        buildList {
            val input = PushRegistrationDatabase.Input(
                pushToken = token
            )
            preferences.getLocalNumber()?.let {
                add(PushRegistrationDatabase.Registration(accountId = it, input = input))
            }

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

    private fun hasCoreIdentity(): Boolean {
        return preferences.getLocalNumber() != null && storage.getUserED25519KeyPair() != null
    }

    companion object {
        private const val TAG = "PushRegistrationHandler"
    }
}
