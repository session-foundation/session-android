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
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.withMutableUserConfigs
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.error.UnhandledStatusCodeException
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.api.server.execute
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.pro.api.GenerateProProofApi
import org.thoughtcrime.securesms.pro.api.ProDetails
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
 * automatically when needed based on the Pro details state, by the [FetchProDetailsWorker].
 */
@HiltWorker
class ProProofGenerationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val apiExecutor: ServerApiExecutor,
    private val proBackendConfig: Provider<ProBackendConfig>,
    private val generateProProofApi: GenerateProProofApi.Factory,
    private val proDetailsRepository: ProDetailsRepository,
    private val loginStateRepository: LoginStateRepository,
    private val configFactory: ConfigFactoryProtocol,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val proMasterKey = requireNotNull(loginStateRepository.peekLoginState()?.seeded?.proMasterPrivateKey) {
            "User must be logged to generate proof"
        }

        val details = checkNotNull(proDetailsRepository.loadState.value.lastUpdated) {
            "Pro details must be available to generate proof"
        }

        check(details.first.status == ProDetails.DETAILS_STATUS_ACTIVE) {
            "Pro status must be active to generate proof"
        }

        return try {
            val rotatingPrivateKey = ED25519.generate(null).secretKey.data

            val proof = apiExecutor.execute(
                ServerApiRequest(
                    proBackendConfig = proBackendConfig.get(),
                    api = generateProProofApi.create(
                        masterPrivateKey = proMasterKey,
                        rotatingPrivateKey = rotatingPrivateKey
                    ),
                )
            ).successOrThrow()

            configFactory.withMutableUserConfigs {
                it.userProfile.setProConfig(ProConfig(
                    proProof = proof,
                    rotatingPrivateKey = rotatingPrivateKey))
            }


            Log.d(WORK_NAME, "Successfully generated a new pro proof expiring at ${Instant.ofEpochMilli(proof.expiryMs)}")
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Log.e(WORK_NAME, "Error generating Pro proof", e)
            if (e is NonRetryableException ||
                // HTTP 403 indicates that the user is not
                e.findCause<UnhandledStatusCodeException>()?.code == 403) {
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