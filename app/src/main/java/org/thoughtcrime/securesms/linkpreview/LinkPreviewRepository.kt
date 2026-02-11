package org.thoughtcrime.securesms.linkpreview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentState
import org.session.libsession.messaging.sending_receiving.attachments.UriAttachment
import org.session.libsession.utilities.MediaTypes
import org.session.libsession.utilities.Util.readFully
import org.session.libsession.utilities.concurrent.SignalExecutors
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.linkpreview.LinkPreviewUtil.OpenGraph
import org.thoughtcrime.securesms.net.CallRequestController
import org.thoughtcrime.securesms.net.CompositeRequestController
import org.thoughtcrime.securesms.net.ContentProxySafetyInterceptor
import org.thoughtcrime.securesms.net.RequestController
import org.thoughtcrime.securesms.providers.BlobUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview

class LinkPreviewRepository {

    companion object {
        private const val TAG = "LinkPreviewRepository"
        private val NO_CACHE: CacheControl = CacheControl.Builder().noCache().build()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(ContentProxySafetyInterceptor())
        .cache(null)
        .build()

    /**
     * Returns a controller that can cancel the in-flight request.
     * Callback receives LinkPreview? where null means "no preview".
     */
    fun getLinkPreview(
        context: Context,
        url: String,
        callback: (LinkPreview?) -> Unit
    ): RequestController {
        val compositeController = CompositeRequestController()

        if (!LinkPreviewUtil.isValidLinkUrl(url)) {
            Log.w(TAG, "Tried to get a link preview for a non-whitelisted domain.")
            callback(null)
            return compositeController
        }

        val metadataController = fetchMetadata(url) { metadata ->
            if (metadata.isEmpty) {
                callback(null)
                return@fetchMetadata
            }

            val imageUrl = metadata.imageUrl
            if (imageUrl.isNullOrEmpty()) {
                // Title may still be null; in that case, preserve behaviour (title required to be non-null in LinkPreview)
                val title = metadata.title
                if (title.isNullOrEmpty()) {
                    callback(null)
                } else {
                    callback(LinkPreview(url, title, thumbnail = null))
                }
                return@fetchMetadata
            }

            val imageController = fetchThumbnail(context, imageUrl) { attachment ->
                val title = metadata.title
                if (title.isNullOrEmpty() && attachment == null) {
                    callback(null)
                } else {
                    callback(LinkPreview(url, title.orEmpty(), attachment))
                }
            }

            compositeController.addController(imageController)
        }

        compositeController.addController(metadataController)
        return compositeController
    }

    private fun fetchMetadata(
        url: String,
        callback: (Metadata) -> Unit
    ): RequestController {
        val call = client.newCall(
            Request.Builder()
                .url(url)
                .removeHeader("User-Agent")
                .addHeader("User-Agent", "WhatsApp")
                .cacheControl(NO_CACHE)
                .build()
        )

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Request failed.", e)
                callback(Metadata.empty())
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Non-successful response. Code: ${response.code}")
                        callback(Metadata.empty())
                        return
                    }

                    val bodyObj = response.body
                    if (bodyObj == null) {
                        Log.w(TAG, "No response body.")
                        callback(Metadata.empty())
                        return
                    }

                    val body = bodyObj.string()
                    val openGraph: OpenGraph = LinkPreviewUtil.parseOpenGraphFields(body)

                    val title: String? = openGraph.title.orNull()
                    var imageUrl: String? = openGraph.imageUrl.orNull()

                    if (!imageUrl.isNullOrEmpty() && !LinkPreviewUtil.isValidMediaUrl(imageUrl)) {
                        Log.i(TAG, "Image URL was invalid or for a non-whitelisted domain. Skipping.")
                        imageUrl = null
                    }

                    if (!imageUrl.isNullOrEmpty() && !LinkPreviewUtil.isValidMimeType(imageUrl)) {
                        Log.i(TAG, "Image URL was invalid mime type. Skipping.")
                        imageUrl = null
                    }

                    callback(Metadata(title = title, imageUrl = imageUrl))
                } catch (e: Exception) {
                    Log.w(TAG, "Exception parsing metadata.", e)
                    callback(Metadata.empty())
                } finally {
                    response.close()
                }
            }
        })

        return CallRequestController(call)
    }

    private fun fetchThumbnail(
        context: Context,
        imageUrl: String,
        callback: (Attachment?) -> Unit
    ): RequestController {
        val call = client.newCall(Request.Builder().url(imageUrl).build())
        val controller = CallRequestController(call)

        SignalExecutors.UNBOUNDED.execute {
            try {
                val response = call.execute()
                if (!response.isSuccessful) {
                    controller.cancel()
                    callback(null)
                    return@execute
                }

                response.use { res ->
                    res.body.byteStream().use { bodyStream ->

                        val data = readFully(bodyStream) ?: run {
                            controller.cancel()
                            callback(null)
                            return@use
                        }

                        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                        val thumbnail = bitmapToAttachment(
                            bitmap,
                            Bitmap.CompressFormat.JPEG,
                            MediaTypes.IMAGE_JPEG
                        )

                        bitmap?.recycle()
                        callback(thumbnail)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Exception during link preview image retrieval.", e)
                controller.cancel()
                callback(null)
            }
        }

        return controller
    }

    private fun bitmapToAttachment(
        bitmap: Bitmap?,
        format: Bitmap.CompressFormat,
        contentType: String
    ): Attachment? {
        if (bitmap == null) return null

        val baos = ByteArrayOutputStream()
        bitmap.compress(format, 80, baos)

        val bytes = baos.toByteArray()
        val uri: Uri = BlobUtils.getInstance().forData(bytes).createForSingleSessionInMemory()

        return UriAttachment(
            uri,
            uri,
            contentType,
            AttachmentState.DOWNLOADING.value,
            bytes.size.toLong(),
            bitmap.width,
            bitmap.height,
            null,
            null,
            false,
            false,
            null
        )
    }

    private data class Metadata(
        val title: String?,
        val imageUrl: String?
    ) {
        val isEmpty: Boolean
            get() = title.isNullOrEmpty() && imageUrl.isNullOrEmpty()

        companion object {
            fun empty() = Metadata(title = null, imageUrl = null)
        }
    }
}
