package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.impl.background.systemjob.setRequiredNetworkRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import network.loki.messenger.libsession_util.Namespace
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.notifications.PushRegistrationHandler.Companion.ARG_ACCOUNT_ID
import org.thoughtcrime.securesms.notifications.PushRegistrationHandler.Companion.ARG_TOKEN
import org.thoughtcrime.securesms.notifications.PushRegistrationHandler.Companion.TAG_PERIODIC
import org.thoughtcrime.securesms.notifications.PushRegistrationHandler.Companion.tokenFingerprint
import java.time.Duration
import java.util.concurrent.TimeUnit

@HiltWorker
class PushRegistrationWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val tokenFetcher: TokenFetcher, // this is only used as a stale-token GUARD
    private val storage: Storage,
    private val configFactory: ConfigFactory,
    private val registry: PushRegistryV2,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(ARG_ACCOUNT_ID)?.let(AccountId::fromStringOrNull)
            ?: return Result.failure()
        val token = inputData.getString(ARG_TOKEN) ?: return Result.failure()

        // Safety guard: if the current token changed, don't register the stale one.
        tokenFetcher.token.value?.let { current ->
            if (current.isNotEmpty() && current != token) {
                Log.d(TAG, "Stale token for $accountId; skipping run.")
                return Result.success() // no errors, we don't want to retry here
            }
        }

        Log.d(TAG, "Registering push token for account: $accountId with token: ${token.substring(0..10)}")

        val (swarmAuth, namespaces) = when (accountId.prefix) {
            IdPrefix.STANDARD -> {
                val auth = requireNotNull(storage.userAuth) {
                    "PushRegistrationWorker requires user authentication to register push notifications"
                }

                // A standard account ID means ourselves, so we use the local auth.
                require(accountId == auth.accountId) {
                    "PushRegistrationWorker can only register the local account ID"
                }

                auth to REGULAR_PUSH_NAMESPACES
            }
            IdPrefix.GROUP -> {
                requireNotNull(configFactory.getGroupAuth(accountId)) to GROUP_PUSH_NAMESPACES
            }
            else -> {
                throw IllegalArgumentException("Unsupported account ID prefix: ${accountId.prefix}")
            }
        }

        return try {
            registry.register(token = token, swarmAuth = swarmAuth, namespaces = namespaces)
            Result.success()
        }
        catch (e: CancellationException) {
            Log.d(TAG, "Push registration cancelled for account: $accountId")
            throw e
        }
        catch (e: NonRetryableException) {
            Log.e(TAG, "Non retryable error while registering push token for account: $accountId", e)
            Result.failure()
        }
        catch (_: Throwable){
            Log.e(TAG, "Error while registering push token for account: $accountId")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "PushRegistrationWorker"


        private fun oneTimeName(id: AccountId) = "pn-register-once-${id.hexString}"
        private fun periodicName(id: AccountId) = "pn-register-periodic-${id.hexString}"

        suspend fun scheduleImmediate(context: Context, id: AccountId, token: String) {
            val data = Data.Builder()
                .putString(ARG_ACCOUNT_ID, id.hexString)
                .putString(ARG_TOKEN, token)
                .build()
            val req = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
                .setConstraints(Constraints(NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(oneTimeName(id), ExistingWorkPolicy.REPLACE, req).await()
        }

        suspend fun ensurePeriodic(context: Context, id: AccountId, token: String, replace: Boolean) {
            val data = Data.Builder()
                .putString(ARG_ACCOUNT_ID, id.hexString)
                .putString(ARG_TOKEN, token) // immutable token snapshot
                .build()
            val req = PeriodicWorkRequestBuilder<PushRegistrationWorker>(
                7, TimeUnit.DAYS,
                1, TimeUnit.DAYS
                )
                .setInputData(data)
                .addTag(TAG_PERIODIC)
                .addTag(ARG_ACCOUNT_ID + id.hexString)
                .addTag(ARG_TOKEN + tokenFingerprint(token))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                periodicName(id),
                if (replace) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP,
                req
            ).await()
        }

        suspend fun cancelAll(context: Context, id: AccountId) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(oneTimeName(id)).await()
            wm.cancelUniqueWork(periodicName(id)).await()
        }
    }
}

private val GROUP_PUSH_NAMESPACES = listOf(
    Namespace.GROUP_MESSAGES(),
    Namespace.GROUP_INFO(),
    Namespace.GROUP_MEMBERS(),
    Namespace.GROUP_KEYS(),
    Namespace.REVOKED_GROUP_MESSAGES(),
)
private val REGULAR_PUSH_NAMESPACES = listOf(Namespace.DEFAULT())
