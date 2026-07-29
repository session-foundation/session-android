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
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.withMutableUserConfigs
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.api.server.execute
import org.thoughtcrime.securesms.auth.LoginStateRepository
import network.loki.messenger.libsession_util.pro.GetProStatusResponse
import org.thoughtcrime.securesms.pro.api.GetProStatusApi
import org.thoughtcrime.securesms.pro.api.ServerApiRequest
import org.thoughtcrime.securesms.pro.api.successOrThrow
import org.thoughtcrime.securesms.pro.db.ProDatabase
import java.time.Duration
import javax.inject.Provider

/**
 * A worker that fetches the user's Pro status from the server and updates the local database.
 *
 * This worker doesn't do any business logic in terms of when to schedule itself, it simply performs
 * the fetch and update operation regardlessly. It, however, does schedule the [ProProofGenerationWorker]
 * if needed based on the fetched Pro status, this is because the proof generation logic
 * is tightly coupled to the fetched Pro status state.
 */
@HiltWorker
class FetchProStatusWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val proBackendConfig: Provider<ProBackendConfig>,
    private val serverApiExecutor: ServerApiExecutor,
    private val getProStatusApiFactory: GetProStatusApi.Factory,
    private val proDatabase: ProDatabase,
    private val loginStateRepository: LoginStateRepository,
    private val snodeClock: SnodeClock,
    private val configFactory: ConfigFactoryProtocol,
    private val prefs: TextSecurePreferences,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!prefs.forcePostPro()) {
            Log.d(TAG, "Pro status fetch skipped because pro is not enabled")
            return Result.success()
        }

        val proMasterKey =
            requireNotNull(loginStateRepository.peekLoginState()?.seeded?.proMasterPrivateKey) {
                "User must be logged in to fetch pro status"
            }

        return try {
            Log.d(TAG, "Fetching Pro status from server")
            val details = serverApiExecutor.execute(
                ServerApiRequest(
                    proBackendConfig = proBackendConfig.get(),
                    api = getProStatusApiFactory.create(proMasterKey)
                )
            ).successOrThrow()

            Log.d(
                TAG,
                "Fetched pro status, status = ${details.userStatus}, " +
                        "autoRenew = ${details.autoRenewing}, expiry = ${details.expiry}"
            )

            configFactory.withMutableUserConfigs { configs ->
                // Capture into a local: `expiry` is a public API property from another module, so
                // Kotlin can't smart-cast the nullable directly on the property access.
                val expiry = details.expiry
                if (expiry != null) {
                    configs.userProfile.setProAccessExpiry(expiry.epochSecond)
                } else {
                    configs.userProfile.removeProAccessExpiry()
                }

                // Remove the pro config only when the backend authoritatively says we are no longer
                // pro (expired) or never were (never). An unknown/future status is NOT a basis to
                // delete it: removeProConfig() writes the SYNCED user profile, so clearing on an
                // unrecognised status would erase a valid proof across all the user's devices. Leave
                // it — the proof's own expiry governs, and the backend won't refresh (or will revoke)
                // a genuinely-lapsed account. We schedule proof generation below if we are still pro.
                if (details.userStatus == ProUserStatus.EXPIRED ||
                    details.userStatus == ProUserStatus.NEVER
                ) {
                    configs.userProfile.removeProConfig()
                }
            }
            proDatabase.updateProStatus(proStatus = details, updatedAt = snodeClock.currentTime())

            scheduleProofGenerationIfNeeded(details)

            Result.success()
        } catch (e: CancellationException) {
            Log.d(TAG, "Work cancelled")
            throw e
        } catch (e: NonRetryableException) {
            Log.e(TAG, "Non-retryable error fetching pro status", e)
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching pro status", e)
            Result.retry()
        }
    }


    private suspend fun scheduleProofGenerationIfNeeded(details: GetProStatusResponse) {
        if (details.userStatus != ProUserStatus.ACTIVE) {
            // Not (yet) Pro — but if a purchase is in flight (possibly synced from another device that
            // bought and set pro_prepaid), keep driving the redemption poll so any device can pull the
            // entitlement through. Otherwise there's nothing to generate.
            val purchasePending = configFactory.withUserConfigs { it.userProfile.getProPrepaid() } != null
            if (purchasePending) {
                Log.d(TAG, "Not active but a purchase is in flight; scheduling proof redemption")
                ProProofGenerationWorker.schedule(context)
            } else {
                Log.d(TAG, "Pro is not active, cancelling any existing proof generation work")
                ProProofGenerationWorker.cancel(context)
            }
            return
        }

        // libsession owns the renewal schedule now — no more client-side autoRenewing/expiry logic (which
        // was inconsistent and skipped non-auto-renewing but still-valid entitlements). getProRenewalTarget
        // returns null (valid proof, no renewal needed), a target <= now (renew now), or a future target
        // (~1h before proof expiry, nudged off the rotation-period boundary so all devices converge).
        val nowSeconds = snodeClock.currentTime().epochSecond
        val target = configFactory.withUserConfigs { it.userProfile.getProRenewalTarget(nowSeconds) }
        if (target == null) {
            Log.d(TAG, "Pro proof is still valid; no renewal needed")
            return
        }

        val delay = Duration.ofSeconds((target - nowSeconds).coerceAtLeast(0L))
        if (delay.isZero) {
            Log.d(TAG, "Pro proof needs (re)generation now, scheduling immediately")
            ProProofGenerationWorker.schedule(context)
        } else {
            Log.d(TAG, "Pro proof renewal due in $delay, scheduling")
            ProProofGenerationWorker.schedule(context, delay)
        }
    }

    companion object {
        private const val TAG = "FetchProStatusWorker"

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
                    request = OneTimeWorkRequestBuilder<FetchProStatusWorker>()
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