package org.thoughtcrime.securesms.notifications

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsession.snode.SnodeClock
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.utilities.ConfigUpdateNotification
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Namespace
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.util.InternetConnectivity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PushRegistrationManager @Inject constructor(
    private val pushRegistry: PushRegistryV2,
    preferences: TextSecurePreferences,
    tokenFetcher: TokenFetcher,
    private val configFactory: ConfigFactory,
    private val clock: SnodeClock,
    connectivity: InternetConnectivity,
    private val storage: StorageProtocol,
) {
    private val subscriptions =
        // Produces a list of account that we should subscribe to. This shall be the source of truth that:
        // 1. Every account in this list must be subscribed to.
        // 2. Everything not in this list must NOT be subscribed to.
        combine(
            preferences.watchLocalNumber(),
            preferences.pushEnabled,
            configFactory.configUpdateNotifications.filter { it is ConfigUpdateNotification.UserConfigsMerged || it == ConfigUpdateNotification.UserConfigsModified },
            tokenFetcher.token.filterNotNull() // Must wait for the token to be available.
        ) { localNumber, pushEnabled, _, token ->
            if (pushEnabled && localNumber != null) {
                val accounts = buildSet {
                    add(AccountId(localNumber))
                    configFactory.withUserConfigs { it.userGroups.allClosedGroupInfo() }
                        .asSequence()
                        .filter { it.shouldPoll }
                        .mapTo(this) { it.groupAccountId }
                }

                accounts to token
            } else {
                emptySet<AccountId>() to token
            }
        }
            .distinctUntilChanged()

            // This scan: for each account in the list, make sure we subscribe to its push service.
            // For accounts that are no longer in the list, we unsubscribe.
            .scan(emptyMap<AccountId, Subscription>()) { previous, (updated, token) ->
                supervisorScope {
                    // Wait for the internet to become available
                    connectivity.networkAvailable.first { it }

                    // Go through the old list and unsubscribe from accounts that are no longer in the new list.
                    for ((account, subscription) in previous) {
                        if (account !in updated) {
                            launch {
                                unregister(subscription)
                            }
                        }
                    }

                    val registrations = updated
                        .asSequence()
                        .map { account ->
                            async {
                                var subscription = previous[account]
                                if (subscription == null || subscription.token != token) {
                                    subscription = runCatching { register(account, token) }
                                        .onFailure {
                                            Log.w(TAG, "Failed to register push for $account", it)
                                        }
                                        .getOrNull()
                                }

                                account to subscription
                            }
                        }
                        .toList()

                    registrations.awaitAll()
                        .filter { it.second != null }
                        .associate { (id, sub) -> id to sub!! }
                }
            }
            .stateIn(GlobalScope, SharingStarted.Eagerly, emptyMap())

    private suspend fun register(account: AccountId, token: String): Subscription {
        val auth: SwarmAuth
        val namespaces: List<Int>

        if (account.prefix == IdPrefix.GROUP) {
            auth = checkNotNull(configFactory.snapshotGroupAuth(account)) { "Group found for $account" }
            namespaces = listOf(
                Namespace.GROUP_INFO(),
                Namespace.GROUP_KEYS(),
                Namespace.GROUP_MEMBERS(),
                Namespace.GROUP_MESSAGES(),
                Namespace.REVOKED_GROUP_MESSAGES(),
            )
        } else {
            auth = checkNotNull(storage.userAuth) { "User auth not found" }
            namespaces = listOf(Namespace.DEFAULT())
        }

        pushRegistry.register(token, auth, namespaces)
        return Subscription(auth, token, clock.currentTimeMills())
    }

    private suspend fun unregister(subscription: Subscription) {
        pushRegistry.unregister(subscription.token, subscription.auth)
    }

    private data class Subscription(
        val auth: SwarmAuth,
        val token: String,
        val subscribedAtMills: Long,
    )

    companion object {
        private const val TAG = "PushRegistrationManager"
    }
}