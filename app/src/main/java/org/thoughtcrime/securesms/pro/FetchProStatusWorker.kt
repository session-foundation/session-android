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
import org.session.libsession.utilities.withMutableUserConfigs
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.api.server.execute
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.pro.api.GetProStatusApi
import org.thoughtcrime.securesms.pro.api.ServerApiRequest
import org.thoughtcrime.securesms.pro.api.successOrThrow
import org.thoughtcrime.securesms.pro.db.ProDatabase
import java.time.Duration
import javax.inject.Provider

/**
 * A worker that fetches the user's Pro status from the server and updates the local database.
 *
 * Performs the fetch and the update, and makes no scheduling decisions — not even its own. Proof
 * renewal is scheduled by [ProStatusManager]'s config watcher, deliberately not from here: keying it
 * to a status response would make renewal a downstream effect of a display fetch.
 */
@HiltWorker
class FetchProStatusWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val proBackendConfig: Provider<ProBackendConfig>,
    private val serverApiExecutor: ServerApiExecutor,
    private val getProStatusApiFactory: GetProStatusApi.Factory,
    private val proDatabase: ProDatabase,
    private val loginStateRepository: LoginStateRepository,
    private val snodeClock: SnodeClock,
    private val configFactory: ConfigFactoryProtocol,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val proMasterKey =
            requireNotNull(loginStateRepository.peekLoginState()?.seeded?.proMasterPrivateKey) {
                "User must be logged in to fetch pro status"
            }

        // Record the attempt before making it, so one that fails still spaces out the next. The
        // success timestamp can't do this job: it is stored as a pair with the response blob, so a
        // failed fetch leaves nothing behind and a failing network was never throttled at all.
        proDatabase.setProStatusLastAttemptAt(snodeClock.currentTime())

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

                // A and G go into synced config beside E, so a linked device has the account state
                // without its own fetch. All three must come from ONE response: coverage is read as
                // `E + G` downstream, so an E stored without its G pairs with whatever G was already
                // there.
                //
                // Written unconditionally. `set_nonzero_int` short-circuits a no-change write on a
                // clean config, so a client-side "only if changed" guard adds nothing, and a
                // presence-based guard would be wrong — the key is erased rather than stored when
                // false, so presence flips on every transition. No `t`/`T` bump either: this is
                // backend-derived state like E and I, not a user profile edit.
                //
                // `details.gracePeriod` is the ACCOUNT-level field, not `latestPayment.gracePeriod`,
                // which reports one store transaction and is not gated on auto-renewing.
                configs.userProfile.setProAutoRenewing(details.autoRenewing)
                configs.userProfile.setProGracePeriod(details.gracePeriod)

                // Remove the pro config only when the backend authoritatively says we are no longer
                // pro (expired) or never were (never). An unknown/future status is NOT a basis to
                // delete it: removeProConfig() writes the SYNCED user profile, so clearing on an
                // unrecognised status would erase a valid proof across all the user's devices. Leave
                // it — the proof's own expiry governs, and the backend won't refresh (or will revoke)
                // a genuinely-lapsed account.
                if (details.userStatus == ProUserStatus.EXPIRED ||
                    details.userStatus == ProUserStatus.NEVER
                ) {
                    // Downgrade guard: never wipe a currently-valid proof (a stale status read vs a
                    // fresh proof another device just landed). At a genuine lapse the proof has also
                    // expired (proof.expiry <= account expiry by backend clamp), so this still passes.
                    val nowSeconds = snodeClock.currentTime().epochSecond
                    val proof = configs.userProfile.getProConfig()?.proProof
                    if (proof == null || proof.expirySeconds <= nowSeconds) {
                        configs.userProfile.removeProConfig()
                    }
                }
            }
            proDatabase.updateProStatus(proStatus = details, updatedAt = snodeClock.currentTime())

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