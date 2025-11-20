package org.thoughtcrime.securesms.pro

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.session.libsession.snode.SnodeClock
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.pro.api.GetProRevocationRequest
import org.thoughtcrime.securesms.pro.api.ProApiExecutor
import org.thoughtcrime.securesms.pro.api.successOrThrow
import org.thoughtcrime.securesms.pro.db.ProDatabase
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

@HiltWorker
class RevocationListPollingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val proDatabase: ProDatabase,
    private val getProRevocationRequestFactory: GetProRevocationRequest.Factory,
    private val proApiExecutor: ProApiExecutor,
    private val snodeClock: SnodeClock,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            val lastTicket = proDatabase.getLastRevocationTicket()
            val response = proApiExecutor.executeRequest(request = getProRevocationRequestFactory.create(lastTicket)).successOrThrow()
            proDatabase.updateRevocations(
                data = response.items,
                newTicket = response.ticket
            )

            proDatabase.pruneRevocations(snodeClock.currentTime())

            return Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }

            Log.e(TAG, "Error polling revocation list", e)
            return if (e is NonRetryableException) {
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "RevocationListPollingWorker"

        private const val WORK_NAME = "RevocationListPollingWorker"

        suspend fun schedule(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<RevocationListPollingWorker>(Duration.ofMinutes(15))
                        .setInitialDelay(0L, TimeUnit.MILLISECONDS)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
                        .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                        .build()
                )
                .await()
        }

        suspend fun cancel(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(WORK_NAME)
                .await()
        }
    }
}