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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import network.loki.messenger.libsession_util.Namespace
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.sending_receiving.notifications.NotificationServer
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.batch.BatchApiExecutor
import org.thoughtcrime.securesms.api.error.UnhandledStatusCodeException
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.api.server.ServerApiRequest
import org.thoughtcrime.securesms.api.server.execute
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.PushRegistrationDatabase
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.util.findCause
import java.time.Duration
import java.time.Instant

/**
 * Worker to process pending push registrations.
 */
@HiltWorker
class PushRegistrationWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val storage: StorageProtocol,
    private val pushRegistrationDatabase: PushRegistrationDatabase,
    private val configFactory: ConfigFactoryProtocol,
    private val loginStateRepository: LoginStateRepository,
    @param:PushNotificationModule.PushProcessingSemaphore
    private val semaphore: Semaphore,
    pushApiBatcher: PushApiBatcher,
    serverApiExecutor: ServerApiExecutor,
    @ManagerScope scope: CoroutineScope,
    private val pushRegisterApiFactory: PushRegisterApi.Factory,
    private val pushUnregisterApiFactory: PushUnregisterApi.Factory,
) : CoroutineWorker(context, params) {
    private val serverApiExecutor: ServerApiExecutor by lazy {
        BatchApiExecutor(
            actualExecutor = serverApiExecutor,
            batcher = pushApiBatcher,
            scope = scope,
        )
    }

    override suspend fun doWork(): Result = semaphore.withPermit {
        val work = pushRegistrationDatabase.getPendingRegistrationWork(
            limit = MAX_REGISTRATIONS_PER_RUN
        )

        Log.d(
            TAG,
            "Processing ${work.register.size} registrations and ${work.unregister.size} unregisters"
        )

        supervisorScope {
            val unregisterJobs = work.unregister
                .map { r ->
                    async {
                        r to runCatching {
                            serverApiExecutor.execute(
                                ServerApiRequest(
                                    serverBaseUrl = NotificationServer.LATEST.url,
                                    serverX25519PubKeyHex = NotificationServer.LATEST.publicKey,
                                    api = pushUnregisterApiFactory.create(
                                        token = r.input.pushToken,
                                        swarmAuth = swarmAuthForAccount(AccountId(r.accountId)),
                                    )
                                )
                            )
                        }
                    }
                }

            val registerJobs = work.register
                .map { r ->
                    async {
                        r to runCatching {
                            serverApiExecutor.execute(
                                ServerApiRequest(
                                    serverBaseUrl = NotificationServer.LATEST.url,
                                    serverX25519PubKeyHex = NotificationServer.LATEST.publicKey,
                                    api = pushRegisterApiFactory.create(
                                        token = r.input.pushToken,
                                        swarmAuth = swarmAuthForAccount(AccountId(r.accountId)),
                                        namespaces = if (AccountId(r.accountId).prefix == IdPrefix.GROUP) {
                                            GROUP_PUSH_NAMESPACES
                                        } else {
                                            REGULAR_PUSH_NAMESPACES
                                        }
                                    )
                                )
                            )
                        }
                    }
                }

            pushRegistrationDatabase.updateRegistrations(
                registerJobs.awaitAll().map { (r, result) ->
                    PushRegistrationDatabase.RegistrationWithState(
                        accountId = r.accountId,
                        input = r.input,
                        state = when {
                            result.isSuccess -> {
                                PushRegistrationDatabase.RegistrationState.Registered(
                                    due = Instant.now()
                                        .plus(Duration.ofDays(RE_REGISTER_INTERVAL_DAYS)),
                                )
                            }

                            result.isFailure -> {
                                val exception = result.exceptionOrNull()!!
                                if (exception.findCause<NonRetryableException>() != null ||
                                    exception.findCause<UnhandledStatusCodeException>()?.code == 403) {
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

            pushRegistrationDatabase.removeRegistrations(unregisterJobs.awaitAll().map {
                if (it.second.isFailure) {
                    Log.e(
                        TAG,
                        "Push unregistration failed: (${it.second.exceptionOrNull()?.message})"
                    )
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


    private fun swarmAuthForAccount(accountId: AccountId): SwarmAuth {
        return when {
            accountId.prefix == IdPrefix.GROUP -> {
                requireNotNull(configFactory.getGroupAuth(accountId)) {
                    "Group auth is required for group push registration"
                }
            }

            accountId == loginStateRepository.requireLocalAccountId() -> {
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
