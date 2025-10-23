package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import network.loki.messenger.libsession_util.Namespace
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
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
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        var now = Instant.now()

        val work = pushRegistrationDatabase.getPendingRegistrationWork(
            now = now,
            limit = MAX_REGISTRATIONS_PER_RUN
        )

        Log.d(TAG, "Processing ${work.register.size} registrations and ${work.unregister.size} unregisters")

        supervisorScope {
            val unregisterResult = work.unregister.map { r ->
                async {
                    runCatching {
                        registry.unregister(
                            token = r.input.pushToken,
                            swarmAuth = swarmAuthForAccount(AccountId(r.accountId)),
                        )
                    }.onSuccess {
                        Log.d(TAG, "Successfully unregistered push token")
                    }.onFailure { exception ->
                        Log.e(TAG, "Failed to unregister push token", exception)
                    }

                    PushRegistrationDatabase.Registration(accountId = r.accountId, input = r.input)
                }
            }

            val registrationResults = work.register.associateWith { r ->
                async {
                    runCatching {
                        val (auth, namespaces) = runCatching {
                            val accountId = AccountId(r.accountId)

                            swarmAuthForAccount(accountId) to (
                                if (accountId.prefix == IdPrefix.GROUP) {
                                    GROUP_PUSH_NAMESPACES
                                } else {
                                    REGULAR_PUSH_NAMESPACES
                                }
                            )
                        }.recoverCatching {
                            throw NonRetryableException("Unable to get auth for account ID", it)
                        }.getOrThrow()

                        registry.register(
                            token = r.input.pushToken,
                            swarmAuth = auth,
                            namespaces = namespaces
                        )
                    }
                }
            }

            pushRegistrationDatabase.updateRegistrations(
                registrationResults.map { (registration, resultDeferred) ->
                    val result = resultDeferred.await()
                    PushRegistrationDatabase.RegistrationWithState(
                        accountId = registration.accountId,
                        input = registration.input,
                        state = when {
                            result.isSuccess -> {
                                PushRegistrationDatabase.RegistrationState.Registered(
                                    due = now.plus(Duration.ofDays(RE_REGISTER_INTERVAL_DAYS)),
                                )
                            }

                            result.isFailure -> {
                                val exception = result.exceptionOrNull()!!
                                if (exception.getRootCause<NonRetryableException>() != null) {
                                    Log.e(TAG, "Push registration failed permanently", exception)
                                    PushRegistrationDatabase.RegistrationState.PermanentError
                                } else {
                                    val numRetried =
                                        (registration.state as? PushRegistrationDatabase.RegistrationState.Error)?.numRetried?.plus(
                                            1
                                        ) ?: 0

                                    Log.e(TAG, "Push registration failed, retried $numRetried times", exception)

                                    // Exponential backoff: 15s, 30s, 1m, 2m, 4m, capped at 4m
                                    PushRegistrationDatabase.RegistrationState.Error(
                                        due = now + Duration.ofSeconds(
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

            pushRegistrationDatabase.removeRegistrations(unregisterResult.awaitAll())
        }

        // Look for the next due registration and enqueue a new worker if needed.
        now = Instant.now()
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

        private const val WORK_TAG = "push-registration-worker"


        private const val MAX_REGISTRATIONS_PER_RUN = 5
        private const val RE_REGISTER_INTERVAL_DAYS = 7L

        private val GROUP_PUSH_NAMESPACES = listOf(
            Namespace.GROUP_MESSAGES(),
            Namespace.GROUP_INFO(),
            Namespace.GROUP_MEMBERS(),
            Namespace.GROUP_KEYS(),
            Namespace.REVOKED_GROUP_MESSAGES(),
        )
        private val REGULAR_PUSH_NAMESPACES = listOf(Namespace.DEFAULT())

        suspend fun enqueue(context: Context, delay: Duration?) {
            val builder = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .addTag(WORK_TAG)

            if (delay != null) {
                builder.setInitialDelay(delay)
            } else {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }

            WorkManager.getInstance(context)
                .enqueue(builder.build())
                .await()

            Log.d(TAG, "Enqueued next worker with delay = $delay")
        }

        /**
         * Best effort attempt to cancel all non-running work.
         */
        suspend fun tryCancellingNonRunningWorks(context: Context) {
            val manager = WorkManager.getInstance(context)

            coroutineScope {
                manager
                    .getWorkInfos(WorkQuery.Builder.fromTags(tags = listOf(WORK_TAG))
                        .addStates(WorkInfo.State.entries.filter { it != WorkInfo.State.RUNNING })
                        .build())
                    .await()
                    .map { work ->
                        launch {
                            manager.cancelWorkById(work.id)
                        }
                    }
                    .joinAll()
            }
        }
    }
}