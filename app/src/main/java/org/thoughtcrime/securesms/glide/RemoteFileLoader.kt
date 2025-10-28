package org.thoughtcrime.securesms.glide

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.attachments.AvatarDownloadManager
import org.thoughtcrime.securesms.dependencies.ManagerScope
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Provider

/**
 * A Glide [ModelLoader] for [RemoteFile]s
 */
class RemoteFileLoader @Inject constructor(
    @param:ManagerScope private val scope: CoroutineScope,
    private val avatarDownloadManager: AvatarDownloadManager,
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

    private inner class RemoteFileDataFetcher(private val file: RemoteFile) :
        DataFetcher<InputStream> {
        private var job: Job? = null

        override fun loadData(
            priority: Priority,
            callback: DataFetcher.DataCallback<in InputStream>
        ) {
            job = scope.launch {
                try {
                    callback.onDataReady(avatarDownloadManager.download(file))

                } catch (e: CancellationException) {
                    Log.i(TAG, "Download cancelled for file: $file")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error downloading file: $file", e)
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
                    messageDigest.update(file.roomId.toByteArray())
                    messageDigest.update(file.fileId.toByteArray())
                }

                is RemoteFile.Encrypted -> {
                    messageDigest.update(file.url.toByteArray())
                    messageDigest.update(file.key.data)
                }
            }
        }
    }

    override fun handles(model: RemoteFile): Boolean = true

    class Factory(private val loaderProvider: Provider<RemoteFileLoader>) : ModelLoaderFactory<RemoteFile, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<RemoteFile, InputStream> {
            return loaderProvider.get()
        }

        override fun teardown() {}
    }

    companion object {
        private const val TAG = "RemoteFileLoader"
    }
}