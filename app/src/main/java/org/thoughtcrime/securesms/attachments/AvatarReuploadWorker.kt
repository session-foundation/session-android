package org.thoughtcrime.securesms.attachments

import android.content.Context
import android.widget.Toast
import androidx.compose.ui.unit.IntSize
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSource
import okio.buffer
import okio.source
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.RemoteFile.Companion.toRemoteFile
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.util.BitmapUtil
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import org.thoughtcrime.securesms.util.DateUtils.Companion.secondsToInstant
import org.thoughtcrime.securesms.util.ImageUtils
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * A worker that periodically checks if the user's avatar needs to be re-uploaded to the server,
 * and when so, reprocesses and re-uploads it, or renews it on the server if no reprocessing is needed.
 *
 * Right now we are scheduling it to run once daily, this should give us plenty of leeway to ensure
 * that the avatar is always available on the server (the default ttl is 14 days).
 */
@HiltWorker
class AvatarReuploadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val attachmentProcessor: AttachmentProcessor,
    private val configFactory: ConfigFactoryProtocol,
    private val avatarUploadManager: Lazy<AvatarUploadManager>,
    private val localEncryptedFileInputStreamFactory: LocalEncryptedFileInputStream.Factory,
    private val fileServerApi: FileServerApi,
    private val prefs: TextSecurePreferences,
    private val currentActivityObserver: CurrentActivityObserver,
) : CoroutineWorker(context, params) {

    /**
     * Log the given message and show a toast if in debug mode
     */
    private suspend inline fun logAndToast(message: String, e: Throwable? = null) {
        Log.d(TAG, message, e)

        val context = currentActivityObserver.currentActivity.value ?: return
        if (prefs.debugAvatarReupload || BuildConfig.DEBUG) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "AvatarReupload[debug only]: $message", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override suspend fun doWork(): Result {
        val (profile, lastUpdated) = configFactory.withUserConfigs { configs ->
            configs.userProfile.getPic().toRemoteFile() to configs.userProfile.getProfileUpdatedSeconds().secondsToInstant()
        }

        if (profile == null) {
            logAndToast("No profile picture set; nothing to do.")
            return Result.success()
        }

        val localFile = AvatarDownloadManager.computeFileName(context, profile)
        if (!localFile.exists()) {
            logAndToast("Avatar file is missing locally; nothing to do.")
            return Result.success()
        }

        // Check if the file exists and whether we need to do reprocessing, if we do, we reprocess and re-upload
        localEncryptedFileInputStreamFactory.create(localFile).use { stream ->
            if (stream.meta.hasPermanentDownloadError) {
                logAndToast("Permanent download error for current avatar; nothing to do.")
                return Result.success()
            }

            val source = stream.source().buffer()

            if ((lastUpdated != null && needsReProcessing(source)) || lastUpdated == null) {
                logAndToast("About to start reuploading avatar.")
                val attachment = attachmentProcessor.processAvatar(
                    data = source,
                    dataSizeHint = null,
                ) ?: return Result.failure()

                Log.d(TAG, "Reuploading avatar with mimeType=${attachment.mimeType}, size=${attachment.imageSize}")

                try {
                    avatarUploadManager.get().uploadAvatar(
                        pictureData = attachment.data,
                        isReupload = true
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NonRetryableException) {
                    logAndToast("Non-retryable error while reuploading avatar.", e)
                    return Result.failure()
                } catch (e: Exception) {
                    logAndToast("Error while reuploading avatar.", e)
                    return Result.retry()
                }

                logAndToast("Successfully reuploaded avatar.")
                return Result.success()
            }
        }

        // Otherwise, we only need to renew the same avatar on the server
        val parsed = fileServerApi.parseAttachmentUrl(profile.url.toHttpUrl())

        logAndToast("Renewing user avatar on ${parsed.fileServer}")
        try {
            fileServerApi.renew(
                fileId = parsed.fileId,
                fileServer = parsed.fileServer,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logAndToast("FileServer renew failed", e)

            // If the server doesn't allow us to renew, and last updated is 12 days ago, then re-upload our avatar
            if ((e is NonRetryableException || e is OnionRequestAPI.HTTPRequestFailedAtDestinationException)) {
                if ((lastUpdated?.isBefore(Instant.now().minus(Duration.ofDays(12)))) == true) {
                    val pictureData =
                        localEncryptedFileInputStreamFactory.create(localFile).use { stream ->
                            check(!stream.meta.hasPermanentDownloadError) {
                                "Permanent download error for avatar where we expect to to false."
                            }
                            stream.readBytes()
                        }

                    try {
                        avatarUploadManager.get().uploadAvatar(
                            pictureData = pictureData,
                            isReupload = true
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logAndToast("Error while reuploading avatar after renew failed.", e)
                        return Result.failure()
                    }

                    logAndToast("Successfully reuploaded avatar after renew failed.")
                } else {
                    logAndToast( "Not reuploading avatar after renew failed; last updated too recent.")
                }

                return Result.success()
            }

            return Result.failure()
        }

        return Result.success()
    }

    private fun needsReProcessing(source: BufferedSource): Boolean {
        if (ImageUtils.isPng(source)) {
            return true
        }
        val bounds = readImageBounds(source)
        Log.d(TAG, "Old avatar bounds: $bounds")
        return bounds.width > AttachmentProcessor.MAX_AVATAR_SIZE_PX.width
                || bounds.height > AttachmentProcessor.MAX_AVATAR_SIZE_PX.height
    }


    companion object {
        private const val TAG = "AvatarReuploadWorker"

        private const val UNIQUE_WORK_NAME = "avatar-reupload"

        private fun readImageBounds(source: BufferedSource): IntSize {
            val r = source.peek().inputStream().use(BitmapUtil::getDimensions)
            return IntSize(r.first, r.second)
        }

        suspend fun schedule(context: Context, prefs: TextSecurePreferences) {
            Log.d(TAG, "Scheduling avatar reupload worker.")

            val request = if (BuildConfig.DEBUG || prefs.debugAvatarReupload) {
                PeriodicWorkRequestBuilder<AvatarReuploadWorker>(
                    Duration.ofMinutes(15)
                ).setInitialDelay(0L, TimeUnit.MILLISECONDS)
            } else {
                PeriodicWorkRequestBuilder<AvatarReuploadWorker>(
                    Duration.ofDays(1),
                    Duration.ofHours(12)
                )
            }
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