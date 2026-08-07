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
import org.thoughtcrime.securesms.pro.api.ProApiResponse
import org.thoughtcrime.securesms.pro.api.ProErrorCode
import org.thoughtcrime.securesms.pro.api.ServerApiRequest
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
            // Rotating key is the deterministic seed derived from the Pro master key for the current
            // time (libsession owns the rotation schedule), so every device converges on the same key
            // instead of each generating a random one. Expand the 32-byte seed to a full keypair.
            val rotatingSeed = ED25519.proRotatingSeed(proMasterKey, snodeClock.currentTime().epochSecond)
            val rotatingPrivateKey = ED25519.generate(rotatingSeed).secretKey.data

            val result = apiExecutor.execute(
                ServerApiRequest(
                    proBackendConfig = proBackendConfig.get(),
                    api = generateProProofApi.create(
                        masterPrivateKey = proMasterKey,
                        rotatingPrivateKey = rotatingPrivateKey
                    ),
                )
            )

            when (result) {
                is ProApiResponse.Success -> {
                    val response = result.data
                    // §5.2 invariant: an `ok` proof response always carries the proof.
                    val proof = requireNotNull(response.proof) { "generate-proof returned ok without a proof" }

                    configFactory.withMutableUserConfigs { configs ->
                        // Upgrade guard: only replace the proof if it extends coverage (monotonic merge;
                        // same-period races round to the same expiry -> byte-identical -> no-op). Avoids
                        // churning a proof another device just landed.
                        val current = configs.userProfile.getProConfig()?.proProof
                        if (current == null || proof.expirySeconds > current.expirySeconds) {
                            configs.userProfile.setProConfig(ProConfig(
                                proProof = proof,
                                rotatingPrivateKey = rotatingPrivateKey))
                        }
                        // Refresh the cached access-expiry from the advisory account_expiry that rides the
                        // proof response, so the renewal path keeps E fresh without a separate get_pro_status.
                        response.accountExpiry?.let { configs.userProfile.setProAccessExpiry(it.epochSecond) }
                    }

                    Log.d(WORK_NAME, "Successfully generated a new pro proof expiring at ${Instant.ofEpochSecond(proof.expirySeconds)}")
                    // Minting the proof is what makes the backend validate the payment and mark the
                    // account active, so a get_pro_status fetched before now (e.g. the one behind the
                    // Pro settings screen right after a purchase) is stale "expired". Refresh the
                    // display-only status so the UI flips to active on its own, instead of the user
                    // having to hit "Check Pro Status" manually.
                    proStatusRepository.requestRefresh(force = true)
                    Result.success()
                }

                is ProApiResponse.Failure -> {
                    val code = result.error.errorCode
                    val notEntitled = code == ProErrorCode.NOT_SUBSCRIBED ||
                        code == ProErrorCode.REVOKED ||
                        code == ProErrorCode.SUBSCRIPTION_EXPIRED
                    when {
                        // A purchase in flight overrides "not entitled": the backend may just not have
                        // ingested the payment yet, so keep polling (WorkManager backoff; the pro_prepaid
                        // 1-week gate eventually terminates it).
                        notEntitled && purchasePending -> Result.retry()

                        notEntitled -> {
                            // Backend authoritatively says we're not (or no longer) entitled. Clear the
                            // synced access-expiry (E) so the renewal loop terminates: libsession's renewal
                            // target now fires on "future E but no proof", so a stale future E left here
                            // would spin. (subscription_expired's past account_expiry is redundant with the
                            // get_pro_status horizon, so we clear E rather than re-set it.) Also drop a now-
                            // defunct credential, guarded so a proof another device just landed survives.
                            configFactory.withMutableUserConfigs { configs ->
                                configs.userProfile.removeProAccessExpiry()
                                val nowSeconds = snodeClock.currentTime().epochSecond
                                val existing = configs.userProfile.getProConfig()?.proProof
                                if (existing == null || existing.expirySeconds <= nowSeconds) {
                                    configs.userProfile.removeProConfig()
                                }
                            }
                            Log.w(WORK_NAME, "Pro proof denied (code=$code); cleared access-expiry, ending the acquire loop")
                            Result.failure()
                        }

                        result.error.isRetryable -> Result.retry()
                        else -> Result.failure()
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Log.e(WORK_NAME, "Error generating Pro proof", e)
            // A raw transport-level 403 (not a parsed slug) likewise means "not entitled" -> stop, unless a
            // purchase is pending (payment may be un-ingested); everything else is a retryable transport error.
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