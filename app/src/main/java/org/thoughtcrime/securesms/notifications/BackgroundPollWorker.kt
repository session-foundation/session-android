package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.sending_receiving.pollers.OpenGroupPollerManager
import org.session.libsession.messaging.sending_receiving.pollers.PollerManager
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.groups.GroupPollerManager
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

@HiltWorker
class BackgroundPollWorker @AssistedInject constructor(
    @Assisted val context: Context,
    @Assisted params: WorkerParameters,
    private val storage: StorageProtocol,
    private val groupPollerManager: GroupPollerManager,
    private val openGroupPollerManager: OpenGroupPollerManager,
    private val pollerManager: PollerManager,
) : CoroutineWorker(context, params) {
    enum class Target {
        ONE_TO_ONE,
        GROUPS,
        OPEN_GROUPS
    }

    companion object {
        private const val TAG = "BackgroundPollWorker"
        private const val REQUEST_TARGETS = "REQUEST_TARGETS"

        fun schedulePeriodic(context: Context, targets: Collection<Target> = Target.entries) {
            Log.v(TAG, "Scheduling periodic work.")
            val interval = 15.minutes
            val builder = PeriodicWorkRequestBuilder<BackgroundPollWorker>(interval.inWholeSeconds, TimeUnit.SECONDS)
            builder.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(interval.inWholeSeconds, TimeUnit.SECONDS)

            val dataBuilder = Data.Builder()
            dataBuilder.putStringArray(REQUEST_TARGETS, targets.map { it.name }.toTypedArray())
            builder.setInputData(dataBuilder.build())

            val workRequest = builder.build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                TAG,
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )
        }

        fun cancelPeriodic(context: Context) {
            Log.v(TAG, "Cancelling periodic work.")
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
        }

        fun scheduleOnce(context: Context, targets: Collection<Target> = Target.entries) {
            Log.v(TAG, "Scheduling single run.")
            val builder = OneTimeWorkRequestBuilder<BackgroundPollWorker>()
            builder.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())

            val dataBuilder = Data.Builder()
            dataBuilder.putStringArray(REQUEST_TARGETS, targets.map { it.name }.toTypedArray())
            builder.setInputData(dataBuilder.build())

            val workRequest = builder.build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }

    override suspend fun doWork(): Result {
        val userAuth = storage.userAuth
        if (userAuth == null) {
            Log.v(TAG, "User not registered yet.")
            return Result.failure()
        }

        // Retrieve the desired targets (defaulting to all if not provided or empty)
        val requestTargets: List<Target> = (inputData.getStringArray(REQUEST_TARGETS) ?: emptyArray())
            .map { enumValueOf<Target>(it) }

        try {
            Log.v(TAG, "Performing background poll for ${requestTargets.joinToString { it.name }}.")
            supervisorScope {
                val tasks = mutableListOf<Deferred<*>>()

                // DMs
                if (requestTargets.contains(Target.ONE_TO_ONE)) {
                    tasks += async {
                        Log.d(TAG, "Polling messages.")
                        pollerManager.pollOnce()
                    }
                }

                // Open groups
                if (requestTargets.contains(Target.OPEN_GROUPS)) {
                    tasks += async {
                        Log.d(TAG, "Polling open groups.")
                        openGroupPollerManager.pollAllOpenGroupsOnce()
                    }
                }

                // Close group
                if (requestTargets.contains(Target.GROUPS)) {
                    tasks += async {
                        Log.d(TAG, "Polling all groups.")
                        groupPollerManager.pollAllGroupsOnce()
                    }
                }

                val caughtException = tasks
                    .fold(null) { acc: Throwable?, result ->
                        try {
                            result.await()
                            acc
                        } catch (ec: Exception) {
                            Log.e(TAG, "Failed to poll due to error.", ec)
                            acc?.also { it.addSuppressed(ec) } ?: ec
                        }
                    }

                if (caughtException != null) {
                    throw caughtException
                }
            }

            return Result.success()
        } catch (exception: Exception) {
            Log.e(TAG, "Background poll failed due to error: ${exception.message}.", exception)
            return Result.retry()
        }
    }

}
