package org.thoughtcrime.securesms.glide

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
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
import kotlinx.coroutines.flow.Flow
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.snode.utilities.await
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.ByteArraySlice.Companion.write
import org.session.libsignal.utilities.toHexString
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Duration

@HiltWorker
class EncryptedFileDownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters
) : RemoteFileDownloadWorker(context, params) {
    private val fileId: String
        get() = requireNotNull(inputData.getString(ARG_FILE_ID)) {
            "EncryptedFileDownloadWorker requires a file ID to download"
        }

    private val folderName: String
        get() = requireNotNull(inputData.getString(ARG_FOLDER)) {
            "EncryptedFileDownloadWorker requires a cache folder name"
        }

    override suspend fun downloadFile(): ByteArraySlice {
        return FileServerApi.download(fileId).await()
    }

    override fun getFilesFromInputData(): DownloadedFiles = getFileForUrl(context, folderName, fileId)

    override fun saveDownloadedFile(from: ByteArraySlice, out: File) {
        // Write the downloaded bytes to a temporary file then move it to the final location.
        // This is done to ensure that the file is fully written before being used.
        val tmpOut = File.createTempFile("downloaded-", null, out.parentFile)
        FileOutputStream(tmpOut).use { it.write(from) }

        require(tmpOut.renameTo(out)) {
            "Failed to rename temporary file ${tmpOut.absolutePath} to ${out.absolutePath}"
        }
    }

    override val debugName: String
        get() = "EncryptedFile(id=$fileId)"

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
                completedFile = File(context.cacheDir, "$folderName/$hash")
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

        fun enqueue(context: Context, fileId: String, cacheFolderName: String): Flow<WorkInfo?> {
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

            val workName = uniqueWorkName(fileId, cacheFolderName)
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    workName,
                    ExistingWorkPolicy.KEEP,
                    request
                )

            return WorkManager.getInstance(context)
                .getWorkInfoByIdFlow(request.id)
        }
    }
}