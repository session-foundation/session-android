package org.thoughtcrime.securesms.glide

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.snode.utilities.await
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.ByteArraySlice.Companion.write
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.toHexString
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Duration

/**
 * A worker that downloads files from Session's file server.
 */
@HiltWorker
class EncryptedFileDownloadWorker @AssistedInject constructor(
    @Assisted val context: Context,
    @Assisted val params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val fileId = requireNotNull(inputData.getString(ARG_FILE_ID)) {
            "EncryptedFileDownloadWorker requires a URL to download"
        }

        val folderName = requireNotNull(inputData.getString(ARG_FOLDER)) {
            "EncryptedFileDownloadWorker requires a cache folder name"
        }

        val files = getFileForUrl(applicationContext, folderName, fileId)

        if (files.completedFile.exists()) {
            Log.i(TAG, "File already downloaded: ${files.completedFile}")
            return@withContext Result.success()
        }

        if (files.permanentErrorMarkerFile.exists()) {
            Log.w(TAG, "Skipping downloading $fileId due to it being marked as a permanent error")
            return@withContext Result.failure()
        }

        Log.d(TAG, "Start downloading file from $fileId onto ${files.completedFile}")

        // Make sure the parent directory exists for the completed file.
        files.completedFile.parentFile?.mkdirs()

        try {
            val bytes = FileServerApi.download(fileId).await()
            Log.d(TAG, "Downloaded ${bytes.len} bytes from file server: $fileId")

            // Write the downloaded bytes to a temporary file then move it to the final location.
            // This is done to ensure that the file is fully written before being used.
            val output = File.createTempFile("download-encrypted-", null, context.cacheDir)
            FileOutputStream(output).use { out ->
                out.write(bytes)
            }

            require(output.renameTo(files.completedFile)) {
                "Failed to rename temporary file ${output.absolutePath} to ${files.completedFile.absolutePath}"
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download file $fileId", e)
            if (e is NonRetryableException) {
                files.permanentErrorMarkerFile.parentFile?.mkdirs()
                if (!files.permanentErrorMarkerFile.createNewFile()) {
                    Log.w(TAG, "Failed to create permanent error marker file: ${files.permanentErrorMarkerFile}")
                }

                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    data class DownloadedFiles(
        val completedFile: File,
        val permanentErrorMarkerFile: File,
    )

    companion object {
        private const val TAG = "EncryptedFileDownloadWorker"

        private const val ARG_FILE_ID = "file_id"
        private const val ARG_FOLDER = "folder"

        // Deterministically get the file path for the given URL, using SHA-256 hash for the
        // filename to ensure uniqueness and avoid collisions.
        fun getFileForUrl(context: Context, folderName: String, url: String): DownloadedFiles {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(url.lowercase().trim().toByteArray())
                .toHexString()

            return DownloadedFiles(
                completedFile = File(context.cacheDir, "$folderName/$hash"),
                permanentErrorMarkerFile = File(context.cacheDir, "$folderName/$hash.error")
            )
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }

        private fun uniqueWorkName(fileId: String, cacheFolderName: String): String {
            return "download-$cacheFolderName-$fileId"
        }

        fun cancel(context: Context, fileId: String, cacheFolderName: String): Operation {
            return WorkManager.getInstance(context).cancelUniqueWork(
                uniqueWorkName(fileId, cacheFolderName)
            )
        }

        fun enqueue(context: Context, fileId: String, cacheFolderName: String): Flow<WorkInfo> {
            val request = OneTimeWorkRequestBuilder<EncryptedFileDownloadWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(5))
                .addTag(TAG)
                .setInputData(
                    Data.Builder()
                        .putString(ARG_FILE_ID, fileId)
                        .putString(ARG_FOLDER, cacheFolderName)
                        .build()
                )
                .build()

            val name = uniqueWorkName(fileId, cacheFolderName)

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    name,
                    ExistingWorkPolicy.REPLACE,
                    request
                )

            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(name)
                .mapNotNull { it.firstOrNull() }
        }
    }
}