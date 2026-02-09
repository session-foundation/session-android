package org.thoughtcrime.securesms.util

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.session.libsession.messaging.file_server.FileServerApis
import org.session.libsession.messaging.file_server.GetClientVersionApi
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.api.server.ServerApiRequest
import org.thoughtcrime.securesms.api.server.execute
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

@Singleton
class VersionDataFetcher @Inject constructor(
    private val application: Application,
) : AuthAwareComponent {

    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState) {
        WorkManager.getInstance(application)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequest.Builder(Worker::class, 4.hours.toJavaDuration())
                    .setInitialDelay(0L, TimeUnit.SECONDS)
                    .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                    .build()
            )
            .await()

        Log.d(TAG, "VersionDataFetcherWorker started")
    }

    override fun onLoggedOut() {
        super.onLoggedOut()

        WorkManager.getInstance(application)
            .cancelUniqueWork(WORK_NAME)

        Log.d(TAG, "VersionDataFetcherWorker cancelled")
    }

    companion object {
        private const val TAG = "VersionDataFetcher"

        private const val WORK_NAME = "VersionDataFetcherWorker"
    }

    @HiltWorker
    class Worker @AssistedInject constructor(
        private val serverApiExecutor: ServerApiExecutor,
        private val getClientVersionApi: Provider<GetClientVersionApi>,
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val clientVersion = serverApiExecutor.execute(
                ServerApiRequest(
                    fileServer = FileServerApis.DEFAULT_FILE_SERVER,
                    api = getClientVersionApi.get()
                )
            )

            Log.d(TAG, "Worker fetched version: $clientVersion")
            return Result.success()
        }
    }
}