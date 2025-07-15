package org.thoughtcrime.securesms.glide

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.util.getRootCause
import java.io.File

/**
 * A worker that downloads files from Session's file server.
 */
abstract class RemoteFileDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    abstract suspend fun downloadFile(): ByteArraySlice
    abstract fun getFilesFromInputData(): DownloadedFiles
    abstract fun saveDownloadedFile(from: ByteArraySlice, out: File)

    abstract val debugName: String

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val files = getFilesFromInputData()

        if (files.completedFile.exists()) {
            Log.i(TAG, "File already downloaded: ${files.completedFile}")
            return@withContext Result.success()
        }

        if (files.permanentErrorMarkerFile.exists()) {
            Log.w(TAG, "Skipping downloading $debugName due to it being marked as a permanent error")
            return@withContext Result.failure()
        }

        Log.d(TAG, "Start downloading file from $debugName onto ${files.completedFile}")

        // Make sure the parent directory exists for the completed file.
        files.completedFile.parentFile?.mkdirs()

        try {
            val bytes = downloadFile()
            Log.d(TAG, "Downloaded ${bytes.len} bytes from file server: $debugName")

            saveDownloadedFile(bytes, files.completedFile)

            Result.success()
        } catch (e: CancellationException) {
            Log.i(TAG, "Download cancelled for file $debugName")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download file $debugName", e)
            if (e is NonRetryableException || (e.getRootCause<OnionRequestAPI.HTTPRequestFailedAtDestinationException>())?.statusCode == 404) {
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
        val permanentErrorMarkerFile: File = File(completedFile.parentFile, "${completedFile.name}.error")
    )

    companion object {
        private const val TAG = "RemoteFileDownloadWorker"
    }
}