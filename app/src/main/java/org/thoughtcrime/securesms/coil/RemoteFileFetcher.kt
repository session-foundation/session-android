package org.thoughtcrime.securesms.coil

import android.content.Context
import androidx.work.WorkInfo
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import okio.FileSystem
import okio.buffer
import okio.source
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsignal.exceptions.NonRetryableException
import org.thoughtcrime.securesms.attachments.LocalEncryptedFileInputStream
import org.thoughtcrime.securesms.attachments.RemoteFileDownloadWorker

class RemoteFileFetcher @AssistedInject constructor(
    @Assisted private val file: RemoteFile,
    @param:ApplicationContext private val context: Context,
    val localEncryptedFileInputStreamFactory: LocalEncryptedFileInputStream.Factory,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val downloadedFile = RemoteFileDownloadWorker.computeFileName(context, file)

        // Check if the file already exists in the local storage, otherwise enqueue a download and
        // wait for it to complete.
        val dataSource = if (!downloadedFile.exists()) {
            RemoteFileDownloadWorker.enqueue(context, file)
                .first { it?.state == WorkInfo.State.FAILED || it?.state == WorkInfo.State.SUCCEEDED }
            DataSource.NETWORK
        } else {
            DataSource.DISK
        }

        val stream = localEncryptedFileInputStreamFactory.create(downloadedFile)

        if (stream.meta.hasPermanentDownloadError) {
            stream.close()
            throw NonRetryableException(
                "File download failed permanently for $file"
            )
        }

        return SourceFetchResult(
            source = ImageSource(
                source = stream.source().buffer(),
                fileSystem = FileSystem.SYSTEM,
                metadata = null
            ),
            mimeType = null,
            dataSource = dataSource
        )
    }

    @AssistedFactory
    abstract class Factory : Fetcher.Factory<RemoteFile> {
        abstract fun create(remoteFile: RemoteFile): RemoteFileFetcher

        override fun create(
            data: RemoteFile,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            return create(data)
        }
    }
}