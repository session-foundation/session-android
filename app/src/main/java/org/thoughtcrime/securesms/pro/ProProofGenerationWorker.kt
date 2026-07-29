package org.thoughtcrime.securesms.pro

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
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
import kotlinx.coroutines.CancellationException
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.pro.ProConfig
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.withMutableUserConfigs
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.error.UnhandledStatusCodeException
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.api.server.execute
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.pro.api.GenerateProProofApi
import org.thoughtcrime.securesms.pro.api.ServerApiRequest
import org.thoughtcrime.securesms.pro.api.successOrThrow
import org.thoughtcrime.securesms.util.findCause
import java.time.Duration
import java.time.Instant
import javax.inject.Provider

/**
 * A worker that generates a new [network.loki.messenger.libsession_util.pro.ProProof] and stores it
 * locally.
 *
 * Normally you don't need to interact with this worker directly, as it is scheduled
 * automatically when needed based on the Pro status state, by the [FetchProStatusWorker].
 */
@HiltWorker
class ProProofGenerationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val apiExecutor: ServerApiExecutor,
    private val proBackendConfig: Provider<ProBackendConfig>,
    private val generateProProofApi: GenerateProProofApi.Factory,
    private val proStatusRepository: ProStatusRepository,
    private val loginStateRepository: LoginStateRepository,
    private val configFactory: ConfigFactoryProtocol,
    private val snodeClock: SnodeClock,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val proMasterKey = requireNotNull(loginStateRepository.peekLoginState()?.seeded?.proMasterPrivateKey) {
            "User must be logged to generate proof"
        }

        // Run when we're already Pro (proof renewal) OR when a purchase is in flight (redemption): in the
        // latter case get_pro_status may not be ACTIVE yet, and calling generate_pro_proof is what pulls
        // the entitlement through once the backend has ingested the payment. If neither holds, nothing to do.
        val isActive = proStatusRepository.loadState.value.lastUpdated?.first?.userStatus == ProUserStatus.ACTIVE
        val purchasePending = configFactory.withUserConfigs { it.userProfile.getProPrepaid() } != null
        if (!isActive && !purchasePending) {
            Log.d(WORK_NAME, "Not Pro and no purchase in flight; nothing to generate")
            return Result.success()
        }

        return try {
            // Rotating key is the deterministic weekly seed derived from the Pro master key (libsession
            // owns the rotation schedule), so every device converges on the same key per rotation period
            // instead of each generating a random one. Expand the 32-byte seed to a full keypair.
            val rotatingSeed = ED25519.proRotatingSeed(proMasterKey, snodeClock.currentTime().epochSecond)
            val rotatingPrivateKey = ED25519.generate(rotatingSeed).secretKey.data

            val response = apiExecutor.execute(
                ServerApiRequest(
                    proBackendConfig = proBackendConfig.get(),
                    api = generateProProofApi.create(
                        masterPrivateKey = proMasterKey,
                        rotatingPrivateKey = rotatingPrivateKey
                    ),
                )
            ).successOrThrow()
            // §5.2 invariant: an `ok` proof response always carries the proof.
            val proof = requireNotNull(response.proof) { "generate-proof returned ok without a proof" }

            configFactory.withMutableUserConfigs {
                it.userProfile.setProConfig(ProConfig(
                    proProof = proof,
                    rotatingPrivateKey = rotatingPrivateKey))
            }


            Log.d(WORK_NAME, "Successfully generated a new pro proof expiring at ${Instant.ofEpochSecond(proof.expirySeconds)}")
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Log.e(WORK_NAME, "Error generating Pro proof", e)
            // 403 / NonRetryable normally means "not entitled" -> stop. But while a purchase is in flight
            // that same "not Pro yet" response just means the backend hasn't ingested the payment yet, so
            // keep polling (WorkManager's exponential backoff is our capped poll; the pro_prepaid 1-week
            // gate checked at the top of doWork() eventually terminates it).
            val notEntitled = e is NonRetryableException ||
                e.findCause<UnhandledStatusCodeException>()?.code == 403
            if (notEntitled && !purchasePending) {
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "ProProofGenerationWorker"

        suspend fun schedule(context: Context, delay: Duration? = null) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ProProofGenerationWorker>()
                        .apply {
                            if (delay != null) {
                                setInitialDelay(delay)
                            }
                        }
                        .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
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