package org.thoughtcrime.securesms.notifications

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import javax.inject.Inject

private const val TAG = "PushRegistrationHandler"

/**
 * A class that listens to the config, user's preference, token changes and
 * register/unregister push notification accordingly.
 *
 * This class DOES NOT handle the legacy groups push notification.
 */
class PushRegistrationHandler
@Inject
constructor(
    private val configFactory: ConfigFactory,
    private val preferences: TextSecurePreferences,
    private val tokenFetcher: TokenFetcher,
    @ApplicationContext private val context: Context,
    private val registry: PushRegistryV2,
    private val storage: Storage,
) {
    @OptIn(DelicateCoroutinesApi::class)
    private val scope: CoroutineScope = GlobalScope

    private var job: Job? = null

    @OptIn(FlowPreview::class)
    fun run() {
        require(job == null) { "Job is already running" }

        job = scope.launch(Dispatchers.Default) {
            combine(
                (configFactory.configUpdateNotifications as Flow<Any>)
                    .debounce(500L)
                    .onStart { emit(Unit) },
                preferences.watchLocalNumber(),
                preferences.pushEnabled,
                tokenFetcher.token,
            ) { _, myAccountId, enabled, token ->
                if (!enabled || myAccountId == null || storage.getUserED25519KeyPair() == null || token.isNullOrEmpty()) {
                    return@combine emptySet<SubscriptionKey>()
                }

                setOf(SubscriptionKey(AccountId(myAccountId), token)) + getGroupSubscriptions(token)
            }
                .scan(emptySet<SubscriptionKey>() to emptySet<SubscriptionKey>()) { acc, current ->
                    acc.second to current
                }
                .collect { (prev, current) ->
                    val added = current - prev
                    val removed = prev - current
                    if (added.isNotEmpty()) {
                        Log.d(TAG, "Adding ${added.size} new subscriptions")
                    }

                    if (removed.isNotEmpty()) {
                        Log.d(TAG, "Removing ${removed.size} subscriptions")
                    }

                    for (key in added) {
                        PushRegistrationWorker.schedule(
                            context = context,
                            token = key.token,
                            accountId = key.accountId,
                        )
                    }

                    supervisorScope {
                        for (key in removed) {
                            PushRegistrationWorker.cancelRegistration(
                                context = context,
                                accountId = key.accountId,
                            )

                            launch {
                                Log.d(TAG, "Unregistering push token for account: ${key.accountId}")
                                try {
                                    val swarmAuth = swarmAuthForAccount(key.accountId)
                                        ?: throw IllegalStateException("No SwarmAuth found for account: ${key.accountId}")

                                    registry.unregister(
                                        token = key.token,
                                        swarmAuth = swarmAuth,
                                    )

                                    Log.d(TAG, "Successfully unregistered push token for account: ${key.accountId}")
                                } catch (e: Exception) {
                                    if (e !is CancellationException) {
                                        Log.e(TAG, "Failed to unregister push token for account: ${key.accountId}", e)
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }

    private fun swarmAuthForAccount(accountId: AccountId): SwarmAuth? {
        return when (accountId.prefix) {
            IdPrefix.STANDARD -> storage.userAuth?.takeIf { it.accountId == accountId }
            IdPrefix.GROUP -> configFactory.getGroupAuth(accountId)
            else -> null // Unsupported account ID prefix
        }
    }

    private fun getGroupSubscriptions(
        token: String
    ): Set<SubscriptionKey> {
        return configFactory.withUserConfigs { it.userGroups.allClosedGroupInfo() }
            .asSequence()
            .filter { it.shouldPoll }
            .mapTo(hashSetOf()) { SubscriptionKey(accountId = AccountId(it.groupAccountId), token = token) }
    }

    private data class SubscriptionKey(val accountId: AccountId, val token: String)
}