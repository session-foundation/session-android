package org.thoughtcrime.securesms.pro

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.session.libsession.network.SnodeClock
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.api.server.execute
import org.thoughtcrime.securesms.pro.api.GetProRevocationApi
import org.thoughtcrime.securesms.pro.api.ServerApiRequest
import org.thoughtcrime.securesms.pro.api.successOrThrow
import org.thoughtcrime.securesms.pro.db.ProDatabase
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlin.coroutines.cancellation.CancellationException

/**
 * A long running worker which periodically polls the revocation list and updates the local database.
 */
@HiltWorker
class RevocationListPollingWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val proDatabase: ProDatabase,
    private val getProRevocationApiFactory: GetProRevocationApi.Factory,
    private val proBackendConfig: Provider<ProBackendConfig>,
    private val serverApiExecutor: ServerApiExecutor,
    private val snodeClock: SnodeClock,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            val lastTicket = proDatabase.getLastRevocationTicket()
            val response = serverApiExecutor.execute(
                ServerApiRequest(
                    proBackendConfig = proBackendConfig.get(),
                    api = getProRevocationApiFactory.create(lastTicket)
                )
            ).successOrThrow()
            proDatabase.updateRevocations(
                data = response.items,
                newTicket = response.ticket,
                retainForSeconds = response.retainForSeconds,
                now = snodeClock.currentTime()
            )

            proDatabase.pruneRevocations(snodeClock.currentTime())

            // Arrange next polling, sanitising the backend's `retry_in` — it goes straight into a
            // OneTimeWorkRequest delay on APPEND unique work, so a bad value either hot-loops
            // against the backend or disables polling outright.
            //
            // The agreed cross-client rule (signed off 2026-07-27):
            //   retry_in <= 0 -> DEFAULT_RETRY_IN_SECONDS (the checklist's 1-day worst case)
            //   otherwise     -> clamp to [MIN, MAX]
            //
            // Absent/zero deliberately does NOT fall through to the 60s floor: "we were told
            // nothing" should mean "try again tomorrow", not "try again in a minute".
            //
            // Purely defensive — the backend hardcodes RETRY_IN = SECONDS_IN_DAY
            // (Session-Pro-Backend server.py:248) and asserts it in tests, so this should never fire
            // against a real backend. Revocation is also not latency-critical by design: the backend
            // sets effective_at = revoked_at + RETRY_IN (server.py:271), giving every client a full
            // poll interval of slack before a revocation takes effect.
            //
            // TODO: consolidate this rule into libsession once libsession owns networking, so the
            //  three clients share one implementation. Client-side is the correct home until then.
            val retryInSeconds = if (response.retryInSeconds <= 0) {
                DEFAULT_RETRY_IN_SECONDS
            } else {
                response.retryInSeconds.coerceIn(MIN_RETRY_IN_SECONDS, MAX_RETRY_IN_SECONDS)
            }
            if (retryInSeconds != response.retryInSeconds) {
                Log.w(TAG, "Adjusted backend retry_in ${response.retryInSeconds}s to ${retryInSeconds}s")
            }

            WorkManager.getInstance(context)
                .beginUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND,
                    OneTimeWorkRequestBuilder<RevocationListPollingWorker>()
                        .setInitialDelay(retryInSeconds, TimeUnit.SECONDS)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
                        .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                        .build()
                )
                .enqueue()

            Log.d(TAG, "Arranged next polling in $retryInSeconds seconds")

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

        /** Floor for a positive `retry_in`, so a small value can't turn polling into a hot loop. */
        private const val MIN_RETRY_IN_SECONDS = 60L

        /**
         * Ceiling for `retry_in`. The important half: without it, a `retry_in` of (say) ten years is
         * honoured and revocation polling is silently disabled for good.
         */
        private const val MAX_RETRY_IN_SECONDS = 24L * 60 * 60

        /**
         * Used when `retry_in` is absent or non-positive — the checklist's "worst case hardcode to
         * 1 day". Matches the backend's own `RETRY_IN` (`server.py:248`).
         */
        private const val DEFAULT_RETRY_IN_SECONDS = MAX_RETRY_IN_SECONDS

        suspend fun schedule(context: Context) {
            WorkManager.getInstance(context)
                .beginUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<RevocationListPollingWorker>()
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
                        .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                        .build()
                )
                .enqueue()
                .await()

            WorkManager.getInstance(context)
        }

        suspend fun cancel(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(WORK_NAME)
                .await()
        }
    }
}