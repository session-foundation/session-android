package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import network.loki.messenger.libsession_util.Namespace
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.sending_receiving.notifications.Response
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.PushRegistrationDatabase
import org.thoughtcrime.securesms.util.getRootCause
import java.time.Duration
import java.time.Instant

/**
 * Worker to process pending push registrations.
 */
@HiltWorker
class PushRegistrationWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val registry: PushRegistryV2,
    private val storage: StorageProtocol,
    private val pushRegistrationDatabase: PushRegistrationDatabase,
    private val configFactory: ConfigFactoryProtocol,
    private val prefs: TextSecurePreferences,
    @param:PushNotificationModule.PushProcessingSemaphore
    private val semaphore: Semaphore,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = semaphore.withPermit {
        val work = pushRegistrationDatabase.getPendingRegistrationWork(
            limit = MAX_REGISTRATIONS_PER_RUN
        )

        Log.d(
            TAG,
            "Processing ${work.register.size} registrations and ${work.unregister.size} unregisters"
        )

        supervisorScope {
            val unregisterResults = async {
                batchRequest(
                    items = work.unregister,
                    buildRequest = { r ->
                        registry.buildUnregisterRequest(
                            r.input.pushToken,
                            swarmAuthForAccount(AccountId(r.accountId))
                        )
                    },
                    sendBatchRequest = registry::unregister
                )
            }

            val registerResults = async {
                batchRequest(
                    items = work.register,
                    buildRequest = { r ->
                        val accountId = AccountId(r.accountId)
                        registry.buildRegisterRequest(
                            token = r.input.pushToken,
                            swarmAuth = swarmAuthForAccount(accountId),
                            namespaces = if (accountId.prefix == IdPrefix.GROUP) {
                                GROUP_PUSH_NAMESPACES
                            } else {
                                REGULAR_PUSH_NAMESPACES
                            }
                        )
                    },
                    sendBatchRequest = registry::register
                )
            }



            pushRegistrationDatabase.updateRegistrations(
                registerResults.await().map { (r, result) ->
                    PushRegistrationDatabase.RegistrationWithState(
                        accountId = r.accountId,
                        input = r.input,
                        state = when {
                            result.isSuccess -> {
                                PushRegistrationDatabase.RegistrationState.Registered(
                                    due = Instant.now().plus(Duration.ofDays(RE_REGISTER_INTERVAL_DAYS)),
                                )
                            }

                            result.isFailure -> {
                                val exception = result.exceptionOrNull()!!
                                if (exception.getRootCause<NonRetryableException>() != null) {
                                    Log.e(TAG, "Push registration failed permanently", exception)
                                    PushRegistrationDatabase.RegistrationState.PermanentError
                                } else {
                                    val numRetried =
                                        (r.state as? PushRegistrationDatabase.RegistrationState.Error)?.numRetried?.plus(
                                            1
                                        ) ?: 0

                                    Log.e(
                                        TAG,
                                        "Push registration failed (${exception.message}), retried $numRetried times",
                                    )

                                    // Exponential backoff: 15s, 30s, 1m, 2m, 4m, capped at 4m
                                    PushRegistrationDatabase.RegistrationState.Error(
                                        due = Instant.now() + Duration.ofSeconds(
                                            15L * (1 shl minOf(
                                                numRetried,
                                                4
                                            ))
                                        ),
                                        numRetried = numRetried,
                                    )
                                }
                            }

                            else -> error("Unreachable")
                        }
                    )
                }
            )

            pushRegistrationDatabase.removeRegistrations(unregisterResults.await().map {
                if (it.second.isFailure) {
                    Log.e(TAG, "Push unregistration failed: (${it.second.exceptionOrNull()?.message})")
                }

                PushRegistrationDatabase.Registration(
                    accountId = it.first.accountId,
                    input = it.first.input
                )
            })
        }

        // Look for the next due registration and enqueue a new worker if needed.
        val now = Instant.now()
        val nextDueTime = pushRegistrationDatabase.getNextProcessTime(now)
        if (nextDueTime != null) {
            // Don't set the delay if the due time is in the past, so the worker runs immediately.
            val delay = if (nextDueTime.isAfter(now)) Duration.between(now, nextDueTime) else null
            enqueue(context, delay)
        } else {
            Log.d(TAG, "No further push registrations scheduled")
        }

        return Result.success()
    }

    private suspend inline fun <T, Req, Res: Response> batchRequest(
        items: List<T>,
        buildRequest: (T) -> Req,
        sendBatchRequest: suspend (Collection<Req>) -> List<Res>,
    ): List<Pair<T, kotlin.Result<Unit>>> {
        val results = ArrayList<Pair<T, kotlin.Result<Unit>>>(items.size)

        val batchRequestItems = mutableListOf<T>()
        val batchRequests = mutableListOf<Req>()

        for (item in items) {
            try {
                val request = buildRequest(item)
                batchRequestItems += item
                batchRequests += request
            } catch (ec: Exception) {
                results += item to kotlin.Result.failure(NonRetryableException("Failed to build a request", ec))
            }
        }

        try {
            val responses = sendBatchRequest(batchRequests)
            responses.forEachIndexed { idx, response ->
                val item = batchRequestItems[idx]
                results += item to when {
                    response.isSuccess() -> kotlin.Result.success(Unit)
                    response.error == 403 -> kotlin.Result.failure(NonRetryableException("Request failed: code = ${response.error}, message = ${response.message}"))
                    else -> kotlin.Result.failure(RuntimeException("Request failed: code = ${response.error}, message = ${response.message}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // If the batch API fails, mark all requests in this batch as failed.
            batchRequestItems.forEach { item ->
                results += item to kotlin.Result.failure(e)
            }
        }

        return results
    }

    private fun swarmAuthForAccount(accountId: AccountId): SwarmAuth {
        return when {
            accountId.prefix == IdPrefix.GROUP -> {
                requireNotNull(configFactory.getGroupAuth(accountId)) {
                    "Group auth is required for group push registration"
                }
            }

            accountId.hexString == prefs.getLocalNumber() -> {
                requireNotNull(storage.userAuth) {
                    "User auth is required for local number push registration"
                }
            }

            else -> error("Invalid account ID")
        }
    }

    companion object {
        private const val TAG = "PushRegistrationWorker"

        private const val WORK_NAME = "push-registration-worker"


        private const val MAX_REGISTRATIONS_PER_RUN = 100
        private const val RE_REGISTER_INTERVAL_DAYS = 7L

        private val GROUP_PUSH_NAMESPACES = listOf(
            Namespace.GROUP_MESSAGES(),
            Namespace.GROUP_INFO(),
            Namespace.GROUP_MEMBERS(),
            Namespace.GROUP_KEYS(),
            Namespace.REVOKED_GROUP_MESSAGES(),
        )
        private val REGULAR_PUSH_NAMESPACES = listOf(Namespace.DEFAULT())

        fun enqueue(context: Context, delay: Duration?): Operation {
            val builder = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))

            if (delay != null) {
                builder.setInitialDelay(delay)
            } else {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }

            val op = WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    uniqueWorkName = WORK_NAME,
                    existingWorkPolicy = ExistingWorkPolicy.REPLACE,
                    request = builder.build()
                )

            Log.d(TAG, "Enqueued next worker with delay = $delay")

            return op
        }
    }
}