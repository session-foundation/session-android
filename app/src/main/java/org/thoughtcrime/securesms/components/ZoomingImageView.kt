package org.thoughtcrime.securesms.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.media.ExifInterface
import android.net.Uri
import android.os.AsyncTask
import android.util.AttributeSet
import android.util.Pair
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.Target
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.github.chrisbanes.photoview.PhotoView
import network.loki.messenger.R
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.components.subsampling.AttachmentBitmapDecoder
import org.thoughtcrime.securesms.components.subsampling.AttachmentRegionDecoder
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader.DecryptableUri
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.util.BitmapDecodingException
import org.thoughtcrime.securesms.util.BitmapUtil
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.IOException

class ZoomingImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) :
    FrameLayout(context, attrs, defStyleAttr) {
    private val photoView: PhotoView
    private val subsamplingImageView: SubsamplingScaleImageView

    interface ZoomImageInteractions {
        fun onImageTapped()
    }

    private var interactor: ZoomImageInteractions? = null

    init {
        inflate(context, R.layout.zooming_image_view, this)

        this.photoView = findViewById(R.id.image_view)

        this.subsamplingImageView = findViewById(R.id.subsampling_image_view)
    }

    private fun getSubsamplingOrientation(context: Context, uri: Uri): Int {
        var exifOrientation = ExifInterface.ORIENTATION_UNDEFINED
        try {
            PartAuthority.getAttachmentStream(context, uri).use { input ->
                val exif = ExifInterface(input)
                exifOrientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF orientation", e)
        }

        return when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSPOSE -> SubsamplingScaleImageView.ORIENTATION_90
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> SubsamplingScaleImageView.ORIENTATION_180
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSVERSE -> SubsamplingScaleImageView.ORIENTATION_270
            else -> SubsamplingScaleImageView.ORIENTATION_0
        }
    }

    fun setInteractor(interactor: ZoomImageInteractions?) {
        this.interactor = interactor
    }

    @SuppressLint("StaticFieldLeak")
    fun setImageUri(glideRequests: RequestManager, uri: Uri, contentType: String) {
        val context = context
        val maxTextureSize = BitmapUtil.getMaxTextureSize()

        object : AsyncTask<Void?, Void?, ImageLoadResult?>() {
            override fun doInBackground(vararg params: Void?): ImageLoadResult? {
                if (MediaUtil.isGif(contentType)) return null
                try {
                    val dimStream = PartAuthority.getAttachmentStream(context, uri)
                    val dimensions = BitmapUtil.getDimensions(dimStream)
                    val orientation = getSubsamplingOrientation(context, uri)

                    return ImageLoadResult(dimensions.first, dimensions.second, orientation)
                } catch (e: Exception) {
                    Log.w(TAG, e)
                    return null
                }
            }

            override fun onPostExecute(result: ImageLoadResult?) {
                Log.i(
                    TAG,
                    "Dimensions: " + (if (result == null) "(null)" else "${result.width} , ${result.height} - orientation: ${result.orientation}")
                )

                if (result == null || (result.width <= maxTextureSize && result.height <= maxTextureSize)) {
                    Log.i(TAG, "Loading in standard image view...")
                    setImageViewUri(glideRequests, uri)
                } else {
                    Log.i(TAG, "Loading in subsampling image view...")
                    setSubsamplingImageViewUri(uri, result.orientation)
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    private fun setImageViewUri(glideRequests: RequestManager, uri: Uri) {
        photoView.visibility = VISIBLE
        subsamplingImageView.visibility = GONE

        photoView.setOnViewTapListener { _, _, _ ->
            if (interactor != null) interactor!!.onImageTapped()
        }

        glideRequests.load(DecryptableUri(uri))
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .dontTransform()
            .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
            .into(photoView)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setSubsamplingImageViewUri(uri: Uri, orientation: Int) {
        subsamplingImageView.setBitmapDecoderFactory(AttachmentBitmapDecoderFactory())
        subsamplingImageView.setRegionDecoderFactory(AttachmentRegionDecoderFactory())
        subsamplingImageView.visibility = VISIBLE
        photoView.visibility = GONE

        subsamplingImageView.orientation = orientation

        val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    interactor?.onImageTapped()
                    return true
                }
            }
        )
        subsamplingImageView.setImage(ImageSource.uri(uri))
        subsamplingImageView.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    fun cleanup() {
        photoView.setImageDrawable(null)
        subsamplingImageView.recycle()
    }

    private class AttachmentBitmapDecoderFactory : DecoderFactory<AttachmentBitmapDecoder> {
        @Throws(IllegalAccessException::class, InstantiationException::class)
        override fun make(): AttachmentBitmapDecoder {
            return AttachmentBitmapDecoder()
        }
    }

    private class AttachmentRegionDecoderFactory : DecoderFactory<AttachmentRegionDecoder> {
        @Throws(IllegalAccessException::class, InstantiationException::class)
        override fun make(): AttachmentRegionDecoder {
            return AttachmentRegionDecoder()
        }
    }

    data class ImageLoadResult(val width: Int, val height: Int, val orientation: Int)

    companion object {
        private val TAG: String = ZoomingImageView::class.java.simpleName
    }
}
