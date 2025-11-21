package org.thoughtcrime.securesms.pro

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import org.session.libsession.snode.SnodeClock
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.pro.api.GetProDetailsRequest
import org.thoughtcrime.securesms.pro.api.ProApiExecutor
import org.thoughtcrime.securesms.pro.api.ProDetails
import org.thoughtcrime.securesms.pro.api.successOrThrow
import org.thoughtcrime.securesms.pro.db.ProDatabase
import java.time.Duration

/**
 * A worker that fetches the user's Pro details from the server and updates the local database.
 *
 * This worker doesn't do any business logic in terms of when to schedule itself, it simply performs
 * the fetch and update operation regardlessly. It, however, does schedule the [ProProofGenerationWorker]
 * if needed based on the fetched Pro details, this is because the proof generation logic
 * is tightly coupled to the fetched Pro details state.
 */
@HiltWorker
class FetchProDetailsWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val apiExecutor: ProApiExecutor,
    private val getProDetailsRequestFactory: GetProDetailsRequest.Factory,
    private val proDatabase: ProDatabase,
    private val loginStateRepository: LoginStateRepository,
    private val snodeClock: SnodeClock,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val proMasterKey =
            requireNotNull(loginStateRepository.peekLoginState()?.seeded?.proMasterPrivateKey) {
                "User must be logged in to fetch pro details"
            }

        return try {
            Log.d(TAG, "Fetching Pro details from server")
            val details = apiExecutor.executeRequest(
                request = getProDetailsRequestFactory.create(proMasterKey)
            ).successOrThrow()

            Log.d(
                TAG,
                "Fetched pro details, status = ${details.status}, expiry = ${details.expiry}"
            )

            proDatabase.updateProDetails(proDetails = details, updatedAt = snodeClock.currentTime())

            scheduleProofGenerationIfNeeded(details)

            Result.success()
        } catch (e: CancellationException) {
            Log.d(TAG, "Work cancelled")
            throw e
        } catch (e: NonRetryableException) {
            Log.e(TAG, "Non-retryable error fetching pro details", e)
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching pro details", e)
            Result.retry()
        }
    }


    private suspend fun scheduleProofGenerationIfNeeded(details: ProDetails) {
        val now = snodeClock.currentTimeMills()

        if (details.status != ProDetails.DETAILS_STATUS_ACTIVE) {
            Log.d(TAG, "Pro is not active, clearing proof")
            ProProofGenerationWorker.cancel(context)
            proDatabase.updateCurrentProProof(null)
        } else {
            val currentProof = proDatabase.getCurrentProProof()

            if (currentProof == null || currentProof.expiryMs <= now) {
                Log.d(
                    TAG,
                    "Pro is active but no valid proof found, scheduling proof generation now"
                )
                ProProofGenerationWorker.schedule(context)
            } else if (currentProof.expiryMs - now <= Duration.ofMinutes(60).toMillis() &&
                details.expiry!!.toEpochMilli() - now > Duration.ofMinutes(60).toMillis() &&
                details.autoRenewing == true
            ) {
                val delay = Duration.ofMinutes((Math.random() * 50 + 10).toLong())
                Log.d(TAG, "Pro proof is expiring soon, scheduling proof generation in $delay")
                ProProofGenerationWorker.schedule(context, delay)
            } else {
                Log.d(
                    TAG,
                    "Pro proof is still valid for a long period, no need to schedule proof generation"
                )
            }
        }
    }

    companion object {
        private const val TAG = "FetchProDetailsWorker"

        fun watch(context: Context): Flow<WorkInfo> {
            val workQuery = WorkQuery.Builder
                .fromUniqueWorkNames(listOf(TAG))
                .build()

            return WorkManager.getInstance(context)
                .getWorkInfosFlow(workQuery)
                .mapNotNull { it.firstOrNull() }
        }

        fun schedule(
            context: Context,
            existingWorkPolicy: ExistingWorkPolicy,
            delay: Duration? = null
        ) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    uniqueWorkName = TAG,
                    existingWorkPolicy = existingWorkPolicy,
                    request = OneTimeWorkRequestBuilder<FetchProDetailsWorker>()
                        .apply {
                            if (delay != null) {
                                setInitialDelay(delay)
                            }
                        }
                        .addTag(TAG)
                        .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
                        .build()
                )
        }

        suspend fun cancel(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(TAG)
                .await()
        }
    }
}