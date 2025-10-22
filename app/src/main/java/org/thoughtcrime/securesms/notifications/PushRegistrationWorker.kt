package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import network.loki.messenger.libsession_util.Namespace
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
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
        val registrations = pushRegistrationDatabase.getDueRegistrations(
            now = Instant.now(),
            limit = MAX_REGISTRATIONS_PER_RUN,
        )

        Log.d(TAG, "Processing ${registrations.size} push registrations")

        supervisorScope {
            val registrationResults = registrations.associateWith { registration ->
                async {
                    runCatching {
                        val (auth, namespaces) = runCatching {
                            val accountId = AccountId(registration.accountId)

                            when {
                                registration.accountId == prefs.getLocalNumber() -> {
                                    requireNotNull(storage.userAuth) {
                                        "User auth is required for local number push registration"
                                    } to REGULAR_PUSH_NAMESPACES
                                }

                                accountId.prefix == IdPrefix.GROUP -> {
                                    requireNotNull(configFactory.getGroupAuth(accountId)) {
                                        "Group auth is required for group push registration"
                                    } to GROUP_PUSH_NAMESPACES
                                }

                                else -> error("Invalid account ID")
                            }
                        }.recoverCatching {
                            throw NonRetryableException("Unable to get auth for account ID", it)
                        }.getOrThrow()

                        registry.register(
                            token = registration.input.pushToken,
                            swarmAuth = auth,
                            namespaces = namespaces
                        )
                    }
                }
            }

            pushRegistrationDatabase.updateRegistrationStates(
                accountIdAndStates = registrationResults.map { (registration, resultDeferred) ->
                    val result = resultDeferred.await()
                    registration.accountId to when {
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
                }
            )
        }

        // Look for the next due registration and enqueue a new worker if needed.
        now = Instant.now()
        val nextDueTime = pushRegistrationDatabase.getNextDueTime(now)
        if (nextDueTime != null) {
            // Don't set the delay if the due time is in the past, so the worker runs immediately.
            val delay = if (nextDueTime.isAfter(now)) Duration.between(now, nextDueTime) else null
            Log.d(TAG, "Next push registration delay = $delay")
            enqueue(context, delay)
        } else {
            Log.d(TAG, "No further push registrations scheduled")
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "PushRegistrationWorker"

        private const val WORK_NAME = "push-registration-worker-v2"


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

            if (delay != null) {
                builder.setInitialDelay(delay)
            }

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    builder.build()
                )
                .await()
        }

        suspend fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME).await()
        }
    }
}