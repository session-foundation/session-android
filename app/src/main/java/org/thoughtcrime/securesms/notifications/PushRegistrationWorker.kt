package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.impl.background.systemjob.setRequiredNetworkRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import network.loki.messenger.libsession_util.Namespace
import org.session.libsession.database.userAuth
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import java.time.Duration

@HiltWorker
class PushRegistrationWorker @AssistedInject constructor(
    @Assisted val context: Context,
    @Assisted val params: WorkerParameters,
    val registry: PushRegistryV2,
    val storage: Storage,
    val configFactory: ConfigFactory,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val accountId = checkNotNull(inputData.getString(ARG_ACCOUNT_ID)
            ?.let(AccountId::fromStringOrNull)) {
            "PushRegistrationWorker requires a valid account ID"
        }

        val token = checkNotNull(inputData.getString(ARG_TOKEN)) {
            "PushRegistrationWorker requires a valid FCM token"
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

        try {
            registry.register(token = token, swarmAuth = swarmAuth, namespaces = namespaces)
            Log.d(TAG, "Successfully registered push token for account: $accountId")
            return Result.success()
        } catch (e: CancellationException) {
            Log.d(TAG, "Push registration cancelled for account: $accountId")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while registering push token for account: $accountId", e)
            return if (e is NonRetryableException) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val ARG_TOKEN = "token"
        private const val ARG_ACCOUNT_ID = "account_id"

        private const val TAG = "PushRegistrationWorker"

        private val GROUP_PUSH_NAMESPACES = listOf(
            Namespace.GROUP_MESSAGES(),
            Namespace.GROUP_INFO(),
            Namespace.GROUP_MEMBERS(),
            Namespace.GROUP_KEYS(),
            Namespace.REVOKED_GROUP_MESSAGES(),
        )

        private val REGULAR_PUSH_NAMESPACES = listOf(Namespace.DEFAULT())

        private fun uniqueWorkName(accountId: AccountId): String {
            return "push-registration-${accountId.hexString}"
        }

        fun schedule(
            context: Context,
            token: String,
            accountId: AccountId,
        ) {
            val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setInputData(
                    Data.Builder().putString(ARG_TOKEN, token)
                        .putString(ARG_ACCOUNT_ID, accountId.hexString).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName = uniqueWorkName(accountId),
                existingWorkPolicy = ExistingWorkPolicy.REPLACE,
                request = request
            )
        }

        suspend fun cancelRegistration(context: Context, accountId: AccountId) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(uniqueWorkName(accountId))
                .await()
        }
    }
}