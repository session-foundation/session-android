package org.thoughtcrime.securesms.glide

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.await
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.utilities.AESGCM
import org.session.libsession.utilities.Conversions
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.Log
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class RemoteFileLoader(
    private val context: Context,
) : ModelLoader<RemoteFile, InputStream> {
    override fun buildLoadData(
        model: RemoteFile,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream> {
        return ModelLoader.LoadData(
            RemoteFileKey(model),
            RemoteFileDataFetcher(model)
        )
    }

    private inner class RemoteFileDataFetcher(private val file: RemoteFile) : DataFetcher<InputStream> {
        private var job: Job? = null

        override fun loadData(
            priority: Priority,
            callback: DataFetcher.DataCallback<in InputStream>
        ) {
            job = GlobalScope.launch {
                try {
                    when (file) {
                        is RemoteFile.Encrypted -> {
                            val fileId = requireNotNull(FileServerApi.getFileIdFromUrl(file.url)) {
                                "Target URL is not supported, must be a session file server url but got: ${file.url}"
                            }

                            val files = EncryptedFileDownloadWorker.getFileForUrl(
                                context,
                                RecipientAvatarDownloadManager.CACHE_FOLDER_NAME,
                                fileId
                            )

                            if (!files.permanentErrorMarkerFile.exists() && !files.completedFile.exists()) {
                                // Files not exists, enqueue a download
                               val state = EncryptedFileDownloadWorker
                                    .enqueue(
                                        context = context,
                                        fileId = fileId,
                                        cacheFolderName = RecipientAvatarDownloadManager.CACHE_FOLDER_NAME
                                    )
                                    .filter { it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED }
                                    .first()

                                Log.d(TAG, "Download worker finished with info: $state")
                            }

                            if (files.permanentErrorMarkerFile.exists()) {
                                throw NonRetryableException("Requested file is marked as a permanent error:")
                            }

                            check(files.completedFile.exists()) {
                                "File not downloaded but no reason is given. Most likely a bug in the download worker."
                            }

                            callback.onDataReady(
                                ByteArrayInputStream(
                                    AESGCM.decrypt(
                                        files.completedFile.readBytes(),
                                        symmetricKey = file.key.data
                                    )
                                )
                            )
                        }

                        is RemoteFile.Community -> TODO("Community file download not implemented yet")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load remote file: $file", e)
                    callback.onLoadFailed(e)
                }
            }
        }

        override fun cleanup() {
            job?.cancel()
            job = null
        }

        override fun cancel() {
            cleanup()
        }

        override fun getDataClass(): Class<InputStream> = InputStream::class.java
        override fun getDataSource(): DataSource = DataSource.REMOTE
    }

    private data class RemoteFileKey(val file: RemoteFile) : Key {
        override fun updateDiskCacheKey(messageDigest: MessageDigest) {
            when (file) {
                is RemoteFile.Community -> {
                    messageDigest.update(file.communityServerBaseUrl.toByteArray())
                    messageDigest.update(Conversions.longToByteArray(file.fileId))
                }

                is RemoteFile.Encrypted -> {
                    messageDigest.update(file.url.toByteArray())
                    messageDigest.update(file.key.data)
                }
            }
        }
    }

    override fun handles(model: RemoteFile): Boolean = true

    class Factory(private val context: Context) : ModelLoaderFactory<RemoteFile, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<RemoteFile, InputStream> {
            return RemoteFileLoader(context)
        }

        override fun teardown() {}
    }

    companion object {
        private const val TAG = "RemoteFileLoader"
    }
}

suspend fun <T> ListenableFuture<T>.await(): T {
    return suspendCoroutine { cont ->
        addListener({
            cont.resumeWith(runCatching { get() })
        }, MoreExecutors.directExecutor())
    }
}