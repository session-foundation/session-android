package org.thoughtcrime.securesms.mms

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import org.session.libsignal.exceptions.InvalidMessageException
import org.session.libsignal.streams.AttachmentCipherInputStream
import org.session.libsignal.utilities.Log
import java.io.File
import java.io.IOException
import java.io.InputStream

internal class AttachmentStreamLocalUriFetcher(
    private val attachment: File,
    private val plaintextLength: Long,
    private val key: ByteArray,
    private val digest: ByteArray?,
) : DataFetcher<InputStream> {

    private var inputStream: InputStream? = null

    override fun loadData(
        priority: Priority,
        callback: DataFetcher.DataCallback<in InputStream>
    ) {
        try {
            val digestBytes = digest ?: throw InvalidMessageException("No attachment digest!")
            inputStream = AttachmentCipherInputStream.createForAttachment(
                attachment,
                plaintextLength,
                key,
                digestBytes
            )
            callback.onDataReady(inputStream)
        } catch (e: IOException) {
            callback.onLoadFailed(e)
        } catch (e: InvalidMessageException) {
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {
        try {
            inputStream?.close()
        } catch (ioe: IOException) {
            Log.w(TAG, "ioe", ioe)
        } finally {
            inputStream = null
        }
    }

    override fun cancel() = Unit

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.LOCAL

    private companion object {
        private val TAG = AttachmentStreamLocalUriFetcher::class.java.simpleName
    }
}
