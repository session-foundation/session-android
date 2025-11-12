package org.thoughtcrime.securesms.pro

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.pro.api.GetProProofRequest
import org.thoughtcrime.securesms.pro.api.GetProRevocationRequest
import org.thoughtcrime.securesms.pro.api.GetProStatusRequest
import org.thoughtcrime.securesms.pro.api.ProApiResponse
import org.thoughtcrime.securesms.pro.api.executeProApiRequest
import org.thoughtcrime.securesms.util.getRootCause
import java.time.Duration
import java.util.concurrent.TimeUnit

@HiltWorker
class ProStatePoller @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val loginStateRepository: LoginStateRepository,
    private val getProStatusRequest: GetProStatusRequest.Factory,
    private val getProProofRequest: GetProProofRequest.Factory,
    private val getProRevocationRequestFactory: GetProRevocationRequest.Factory,
    private val json: Json,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Log.d(TAG, "Polling Pro state...")
        try {
            val (masterPrivateKey, proState) = loginStateRepository.ensureProMasterAndRotatingState()

            when (val resp = OnionRequestAPI.executeProApiRequest(
                json = json,
                request = getProProofRequest.create(
                    masterPrivateKey = masterPrivateKey,
                    rotatingPrivateKey = proState.rotatingKeyPair.secretKey.data,
                )
            )) {
                is ProApiResponse.Success -> {
                    Log.d(TAG, "Got latest pro proof: ProProof(expiring = ${resp.data.expiryMs})")
                    loginStateRepository.update { oldState ->
                        requireNotNull(oldState) { "No logged in state when polling Pro state" }
                            .copy(proState =
                                (oldState.proState ?: proState).copy(proProof = resp.data))
                    }
                }

                is ProApiResponse.Failure -> {
                    Log.e(TAG, "Error polling Pro proof: $resp")
                    return Result.retry()
                }
            }

            return Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Log.e(TAG, "Error while polling pro state", e)

            if (e.getRootCause<NonRetryableException>() != null) {
                return Result.failure()
            }

            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "ProStatePoller"

        private const val WORK_NAME = "ProStatePoller"

        fun schedule(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    androidx.work.PeriodicWorkRequestBuilder<ProStatePoller>(
                        repeatInterval = Duration.ofMinutes(15)
                    ).setConstraints(
                        Constraints(requiredNetworkType = NetworkType.CONNECTED)
                    ).setInitialDelay(5, TimeUnit.SECONDS)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                        .build()
                )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(WORK_NAME)
        }
    }
}