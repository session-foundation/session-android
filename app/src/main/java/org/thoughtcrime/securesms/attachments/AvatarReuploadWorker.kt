package org.thoughtcrime.securesms.attachments

import android.content.Context
import android.os.FileObserver
import android.os.FileObserver.CREATE
import android.os.FileObserver.MOVED_TO
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.await
import coil3.decode.DecodeUtils
import coil3.decode.StaticImageDecoder
import coil3.gif.isGif
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.BufferedSource
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.recipients.RemoteFile.Companion.toRemoteFile
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.util.DateUtils.Companion.secondsToInstant
import java.io.File
import java.time.Duration
import kotlin.coroutines.resume

@HiltWorker
class AvatarReuploadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val attachmentProcessor: AttachmentProcessor,
    private val configFactory: ConfigFactoryProtocol,
    private val avatarUploadManager: Lazy<AvatarUploadManager>,
    private val localEncryptedFileInputStreamFactory: LocalEncryptedFileInputStream.Factory,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val (profile, lastUpdated) = configFactory.withUserConfigs { configs ->
            configs.userProfile.getPic().toRemoteFile() to configs.userProfile.getProfileUpdatedSeconds().secondsToInstant()
        }

        if (profile == null) {
            Log.d(TAG, "No profile picture set; nothing to do.")
            return Result.success()
        }

        val localFile = RemoteFileDownloadWorker.computeFileName(context, profile)
        waitUntilExists(localFile)

        if (lastUpdated != null) {
            localEncryptedFileInputStreamFactory.create(localFile).use { inputStream ->
                StaticImageDecoder
            }
        }

        TODO("Not yet implemented")
    }


    private suspend fun waitUntilExists(file: File) {
        if (file.exists()) return

        // First make sure its parent directory exists so we can observe it
        file.parentFile!!.mkdirs()

        var fileObserver: FileObserver

        return suspendCancellableCoroutine { cont ->
            // The fileObserver variable MUST exist until the coroutine is resumed or cancelled,
            // otherwise the observer will be garbage collected and the coroutine will never resume.
            @Suppress("DEPRECATION")
            fileObserver = object : FileObserver(
                file.parentFile!!.absolutePath,
                CREATE or MOVED_TO
            ) {
                override fun onEvent(event: Int, path: String?) {
                    Log.d(TAG, "FileObserver event: $event, path: $path")
                    if (path == file.name) {
                        stopWatching()
                        cont.resume(Unit)
                    }
                }
            }

            fileObserver.startWatching()
        }
    }


    companion object {
        private const val TAG = "AvatarReuploadWorker"

        private const val UNIQUE_WORK_NAME = "avatar-reupload"

        private fun isPng(source: BufferedSource): Boolean {
            val pngSignature = byteArrayOf(
                0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
                0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
            )

            return source.peek().readByteArray(pngSignature.size.toLong())
                .contentEquals(pngSignature)
        }

        suspend fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AvatarReuploadWorker>(
                Duration.ofDays(1),
                Duration.ofHours(12)
            )
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(15))
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
                .await()
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}