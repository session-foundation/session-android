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
 * Normally you don't need to interact with this worker directly:
 * [ProStatusManager.manageProofRenewalScheduling] schedules it off libsession's
 * `pro_renewal_target`, recomputed whenever the config inputs to that target change.
 */
@HiltWorker
class ProProofGenerationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val apiExecutor: ServerApiExecutor,
    private val proBackendConfig: Provider<ProBackendConfig>,
    private val generateProProofApi: GenerateProProofApi.Factory,
    private val loginStateRepository: LoginStateRepository,
    private val configFactory: ConfigFactoryProtocol,
    private val snodeClock: SnodeClock,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val proMasterKey = requireNotNull(loginStateRepository.peekLoginState()?.seeded?.proMasterPrivateKey) {
            "User must be logged to generate proof"
        }

        // Whether a proof is wanted at all — proof renewal, a purchase in flight (redemption, where
        // get_pro_status is not ACTIVE yet and minting the proof is what pulls the entitlement
        // through), or an entitlement held with no proof to attach.
        //
        // Ask CONFIG, never `proStatusRepository.loadState`. WorkManager persists this worker's
        // schedule across process death and `loadState` does not, restarting at `Init` — so an
        // in-memory check reads "not active, no purchase" and returns without renewing, for exactly
        // the renewal that came due while the app was dead.
        val now = snodeClock.currentTime()
        val (renewalTarget, purchasePending, proof) = configFactory.withUserConfigs { configs ->
            Triple(
                configs.userProfile.getProRenewalTarget(now.epochSecond),
                configs.userProfile.getProPrepaid() != null,
                configs.userProfile.getProConfig()?.proProof,
            )
        }

        if (renewalTarget == null) {
            Log.d(WORK_NAME, "No Pro proof renewal is due; nothing to generate")
            return Result.success()
        }

        // Pace acquisition. Without a floor this path is a closed loop: a successful generate writes
        // the new proof to config, ProStatusManager.manageProofRenewalScheduling re-reads the renewal
        // target off that write, and `target = proofExpiry - PRO_RENEWAL_LEAD` is permanently in the
        // past whenever a proof lives for less than the 60-minute lead — so it reschedules us
        // immediately, forever.
        //
        // Mirrors iOS `SessionProManager.reconcileProofRenewal` and Desktop `ducks/proBackendData.ts`,
        // constants included. Note it RE-ARMS rather than skipping: `target <= now` is the normal
        // "renewal due" signal, so dropping the work would break real renewals.
        val covered = proof?.let { it.expirySeconds > now.epochSecond } == true

        if (covered) darkAttempt = 0
        val intervalSeconds = if (covered) {
            COVERED_INTERVAL_SECONDS
        } else {
            (DARK_STEP_SECONDS * darkAttempt).coerceAtMost(DARK_CAP_SECONDS)
        }

        val sinceLast = now.epochSecond - lastProofRequestAt
        if (sinceLast < intervalSeconds) {
            val waitSeconds = intervalSeconds - sinceLast
            Log.d(
                WORK_NAME,
                "Last proof request was ${sinceLast}s ago (interval ${intervalSeconds}s, " +
                    "covered=$covered); re-arming in ${waitSeconds}s"
            )
            schedule(applicationContext, Duration.ofSeconds(waitSeconds))
            return Result.success()
        }

        // Count the attempt before making it, so one that fails still advances the backoff.
        lastProofRequestAt = now.epochSecond
        if (!covered) darkAttempt++

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

                        // The renewing flag and the grace period must travel with the expiry above:
                        // coverage end is derived as `E + G`, so a fresh E beside a G from an older
                        // response is wrong by the difference between them.
                        //
                        // Do not hoist these two out of the success branch. Their protection is
                        // PLACEMENT — not the parse, and not the type. libsession's `parse_pro_proof`
                        // returns on the failure path before filling them, so every non-OK outcome
                        // leaves struct defaults of grace 0 and renewing false; the C struct has no
                        // presence flag and the Kotlin type is non-nullable, so a read outside this
                        // branch cannot tell that from a backend that said "not renewing".
                        //
                        // Writing `false` to a presence-only config key ERASES it. On
                        // `subscription_expired`/`not_subscribed`/`revoked` erasing is truthful; on a
                        // protocol error or transport failure it would wipe a flag `get_pro_status`
                        // had correctly learned, on the strength of a response that said nothing.
                        configs.userProfile.setProAutoRenewing(response.accountAutoRenewing)
                        configs.userProfile.setProGracePeriod(response.accountGracePeriod)
                    }

                    Log.d(WORK_NAME, "Successfully generated a new pro proof expiring at ${Instant.ofEpochSecond(proof.expirySeconds)}")
                    // No status refresh requested here, deliberately. Minting the proof does flip the
                    // account active at the backend, but the `setProAccessExpiry` write above already
                    // fires the (E, prepaid) config-change trigger, which schedules that fetch.
                    // Requesting one here as well would make the proof loop a SOURCE of status
                    // fetches, which is the coupling this design keeps out.
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

        /**
         * Minimum spacing between proof requests. **Shared cross-client contract** — iOS
         * (`SessionProManager.reconcileProofRenewal`) and Desktop use exactly these values; keep them
         * in step, and say why in the commit if they ever have to diverge.
         */
        private const val COVERED_INTERVAL_SECONDS = 60L   // holding a valid proof: brisk
        private const val DARK_STEP_SECONDS = 15L          // no valid proof: 15s * attempt …
        private const val DARK_CAP_SECONDS = 900L          // … capped at 15 minutes

        /**
         * Pacing state, deliberately in-memory to match iOS and Desktop, which both hold it as an
         * ordinary field. A process restart resets it, costing at most one extra request per launch
         * — the loop this guards against was a tight re-schedule cycle within a single process.
         */
        // 0 rather than a sentinel minimum: `now - lastProofRequestAt` would overflow from Long.MIN_VALUE
        // and come out negative, throttling the very first request instead of letting it through.
        @Volatile private var lastProofRequestAt = 0L
        @Volatile private var darkAttempt = 0

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