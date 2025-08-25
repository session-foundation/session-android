package org.thoughtcrime.securesms.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
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

        subsamplingImageView.orientation = SubsamplingScaleImageView.ORIENTATION_USE_EXIF
    }

    fun setInteractor(interactor: ZoomImageInteractions?) {
        this.interactor = interactor
    }

    @SuppressLint("StaticFieldLeak")
    fun setImageUri(glideRequests: RequestManager, uri: Uri, contentType: String) {
        val context = context
        val maxTextureSize = BitmapUtil.getMaxTextureSize()

        Log.i(
            TAG,
            "Max texture size: $maxTextureSize"
        )

        object : AsyncTask<Void?, Void?, Pair<Int, Int>?>() {
            override fun doInBackground(vararg params: Void?): Pair<Int, Int>? {
                if (MediaUtil.isGif(contentType)) return null

                try {
                    val inputStream = PartAuthority.getAttachmentStream(context, uri)
                    return BitmapUtil.getDimensions(inputStream)
                } catch (e: IOException) {
                    Log.w(TAG, e)
                    return null
                } catch (e: BitmapDecodingException) {
                    Log.w(TAG, e)
                    return null
                }
            }

            override fun onPostExecute(dimensions: Pair<Int, Int>?) {
                Log.i(
                    TAG,
                    "Dimensions: " + (if (dimensions == null) "(null)" else dimensions.first.toString() + ", " + dimensions.second)
                )

                if (dimensions == null || (dimensions.first <= maxTextureSize && dimensions.second <= maxTextureSize)) {
                    Log.i(TAG, "Loading in standard image view...")
                    setImageViewUri(glideRequests, uri)
                } else {
                    Log.i(TAG, "Loading in subsampling image view...")
                    setSubsamplingImageViewUri(uri)
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
    private fun setSubsamplingImageViewUri(uri: Uri) {
        subsamplingImageView.setBitmapDecoderFactory(AttachmentBitmapDecoderFactory())
        subsamplingImageView.setRegionDecoderFactory(AttachmentRegionDecoderFactory())

        subsamplingImageView.visibility = VISIBLE
        photoView.visibility = GONE

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

    companion object {
        private val TAG: String = ZoomingImageView::class.java.simpleName
    }
}
