package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.guava.await
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
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PN registration source of truth using per-account periodic workers.
 *
 * Periodic workers must be created with tags:
 *  - "pn-register-periodic"
 *  - "pn-acc-<hexAccountId>"
 *  - "pn-tfp-<tokenFingerprint>"
 *
 */
@Singleton
class PushRegistrationHandler @Inject constructor(
    private val configFactory: ConfigFactory,
    private val preferences: TextSecurePreferences,
    private val tokenFetcher: TokenFetcher,
    @ApplicationContext private val context: Context,
    private val registry: PushRegistryV2,
    private val storage: Storage,
    @ManagerScope private val scope: CoroutineScope
) : OnAppStartupComponent {

    private var job: Job? = null

    @OptIn(FlowPreview::class)
    override fun onPostAppStarted() {
        require(job == null) { "Job is already running" }

        job = scope.launch(Dispatchers.Default) {
            combine(
                (configFactory.configUpdateNotifications as Flow<Any>)
                    .debounce(500L)
                    .onStart { emit(Unit) },
                preferences.watchLocalNumber(),
                preferences.pushEnabled,
                tokenFetcher.token
            ) { _, _, enabled, token ->
                val desired =
                    if (enabled && hasCoreIdentity())
                        desiredSubscriptions()
                    else emptySet()
                Triple(enabled, token, desired)
            }
                .distinctUntilChanged()
                .collect { (pushEnabled, token, desiredIds) ->
                try {
                    reconcileWithWorkManager(pushEnabled, token, desiredIds)
                } catch (t: Throwable) {
                    Log.e(TAG, "Reconciliation failed", t)
                }
            }
        }
    }

    private suspend fun reconcileWithWorkManager(
        pushEnabled: Boolean,
        token: String?,
        activeAccounts: Set<AccountId>
    ) {
        val wm = WorkManager.getInstance(context)

        // Read existing push periodic workers and parse (AccountId, tokenFingerprint) from tags.
        val periodicInfos = wm.getWorkInfosByTag(TAG_PERIODIC).await()
            .filter { it.state != WorkInfo.State.CANCELLED && it.state != WorkInfo.State.FAILED }

        Log.d(TAG, "We currently have ${periodicInfos.size} push periodic workers")

        val accountsAlreadyRegistered: Map<AccountId, String> = buildMap {
            for (info in periodicInfos) {
                val id = parseAccountId(info) ?: continue
                val token = parseTokenFingerprint(info) ?: continue
                put(id, token)
            }
        }

        // If push disabled or identity missing → cancel all and try to deregister.
        if (!pushEnabled || !hasCoreIdentity()) {
            val toCancel = accountsAlreadyRegistered.keys
            if (toCancel.isNotEmpty()) {
                Log.d(TAG, "Push disabled/identity missing; cancelling ${toCancel.size} PN periodic works")
            }
            supervisorScope {
                toCancel.forEach { id ->
                    launch {
                        PushRegistrationWorker.cancelAll(context, id)
                        tryUnregister(token, id)
                    }
                }
            }
            return
        }

        val currentFingerprint = token?.let { tokenFingerprint(it) }

        // Add missing (ensure periodic + run now) — only if we have a token.
        val accountsToAdd = activeAccounts - accountsAlreadyRegistered.keys
        if (accountsToAdd.isNotEmpty()) Log.d(TAG, "Adding ${accountsToAdd.size} PN registrations")
        if (!token.isNullOrEmpty()) {
            accountsToAdd.forEach { id ->
                PushRegistrationWorker.ensurePeriodic(context, id, token, replace = false) // KEEP
                PushRegistrationWorker.scheduleImmediate(context, id, token)               // run now
            }
        }

        // Token rotation: replace periodic where fingerprint mismatches.
        if (!token.isNullOrEmpty()) {
            var replaced = 0
            activeAccounts.forEach { id ->
                val tokenFingerprint = accountsAlreadyRegistered[id] ?: return@forEach
                if (tokenFingerprint != currentFingerprint) {
                    PushRegistrationWorker.ensurePeriodic(context, id, token, replace = true) // REPLACE
                    PushRegistrationWorker.scheduleImmediate(context, id, token)
                    replaced++
                }
            }
            if (replaced > 0) Log.d(TAG, "Replaced $replaced periodic PN workers due to token rotation")
        }

        // Removed subscriptions: cancel workers & attempt deregister.
        val accountToRemove = accountsAlreadyRegistered.keys - activeAccounts
        if (accountToRemove.isNotEmpty()) Log.d(TAG, "Removing ${accountToRemove.size} PN registrations")
        supervisorScope {
            accountToRemove.forEach { id ->
                launch {
                    PushRegistrationWorker.cancelAll(context, id)
                    tryUnregister(token, id)
                }
            }
        }
    }

    /**
     * Build desired subscriptions: self (local number) + any group that shouldPoll.
     * */
    private fun desiredSubscriptions(): Set<AccountId> = buildSet {
        preferences.getLocalNumber()?.let { add(AccountId(it)) }
        val groups = configFactory.withUserConfigs { it.userGroups.allClosedGroupInfo() }
        groups.filter { it.shouldPoll }
            .mapTo(this) { AccountId(it.groupAccountId) }
    }

    private fun hasCoreIdentity(): Boolean {
        return preferences.getLocalNumber() != null && storage.getUserED25519KeyPair() != null
    }

    /**
     * Try to deregister if we still have credentials and a token to sign with.
     * Safe to no-op if token/auth missing (e.g., keys already deleted).
     */
    private suspend fun tryUnregister(token: String?, accountId: AccountId) {
        if (token.isNullOrEmpty()) return
        val auth = swarmAuthForAccount(accountId) ?: return
        try {
            Log.d(TAG, "Unregistering PN for $accountId")
            registry.unregister(token = token, swarmAuth = auth)
            Log.d(TAG, "Unregistered PN for $accountId")
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(TAG, "Unregister failed for $accountId", e)
            } else {
                throw e
            }
        }
    }

    private fun swarmAuthForAccount(accountId: AccountId): SwarmAuth? {
        return when (accountId.prefix) {
            IdPrefix.STANDARD -> storage.userAuth?.takeIf { it.accountId == accountId }
            IdPrefix.GROUP    -> configFactory.getGroupAuth(accountId)
            else              -> null
        }
    }

    private fun parseAccountId(info: WorkInfo): AccountId? {
        val tag = info.tags.firstOrNull { it.startsWith(ARG_ACCOUNT_ID) } ?: return null
        val hex = tag.removePrefix(ARG_ACCOUNT_ID)
        return AccountId.fromStringOrNull(hex)
    }

    private fun parseTokenFingerprint(info: WorkInfo): String? {
        val tag = info.tags.firstOrNull { it.startsWith(ARG_TOKEN) } ?: return null
        return tag.removePrefix(ARG_TOKEN)
    }

    companion object {
        private const val TAG = "PushRegistrationHandler"

        const val TAG_PERIODIC   = "pn-register-periodic"
        const val ARG_ACCOUNT_ID = "pn-account-"
        const val ARG_TOKEN = "pn-token-"

        fun tokenFingerprint(token: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
            val short = digest.copyOfRange(0, 8) // 64 bits is plenty for equality checks
            @Suppress("InlinedApi")
            return android.util.Base64.encodeToString(
                short,
                android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
            )
        }
    }
}
