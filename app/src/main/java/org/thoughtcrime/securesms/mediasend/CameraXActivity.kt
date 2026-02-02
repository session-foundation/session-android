package org.thoughtcrime.securesms.mediasend

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.LifecycleCameraController
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.databinding.ActivityCameraxBinding
import org.session.libsession.utilities.MediaTypes
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import org.thoughtcrime.securesms.providers.BlobUtils
import org.thoughtcrime.securesms.util.applySafeInsetsPaddings
import org.thoughtcrime.securesms.util.setSafeOnClickListener
import org.thoughtcrime.securesms.webrtc.Orientation
import org.thoughtcrime.securesms.webrtc.OrientationManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class CameraXActivity : ScreenLockActionBarActivity() {

    override val applyDefaultWindowInsets: Boolean
        get() = false

    companion object {
        private const val TAG = "CameraXActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_IMAGE_SIZE = "extra_image_size"
        const val EXTRA_IMAGE_WIDTH = "extra_image_width"
        const val EXTRA_IMAGE_HEIGHT = "extra_image_height"

        const val KEY_MEDIA_SEND_COUNT ="key_mediasend_count"
    }

    private lateinit var binding: ActivityCameraxBinding

    private lateinit var cameraController: LifecycleCameraController
    private lateinit var cameraExecutor: ExecutorService
    private var cameraInitialized = false

    private var lastRotation: Orientation = Orientation.UNKNOWN

    private val portraitConstraints = ConstraintSet()
    private val landscapeConstraints = ConstraintSet()
    private lateinit var rootConstraintLayout: ConstraintLayout

    private var orientationManager = OrientationManager(this)

    @Inject
    lateinit var prefs: TextSecurePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraxBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        rootConstraintLayout = binding.root

        // 1) Portrait constraints: from a portrait layout
        portraitConstraints.clone(this, R.layout.activity_camerax_portrait)
        // 2) Landscape constraints: cloned from a template XML
        landscapeConstraints.clone(this, R.layout.activity_camerax_landscape)

        setupUi()
        applyViewInsets()
        initializeCountButton()

        // Permissions should ideally be handled before launching this Activity,
        // but keep this as a safety check.
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        lifecycleScope.launch {
            orientationManager.orientation.collect { orientation ->
                if (!orientationManager.isAutoRotateOn()) {
                    updateUiForRotation(orientation)
                }
            }
        }
    }

    private fun setupUi() {
        binding.cameraCaptureButton.setSafeOnClickListener { takePhoto() }
        binding.cameraFlipButton.setSafeOnClickListener { flipCamera() }
        binding.cameraCloseButton.setSafeOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        // Work out a resolution based on available memory
        val activityManager =
            getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryClassMb = activityManager.memoryClass
        val preferredResolution: Size = when {
            memoryClassMb >= 256 -> Size(1920, 1440)
            memoryClassMb >= 128 -> Size(1280, 960)
            else -> Size(640, 480)
        }
        Log.d(
            TAG,
            "Selected resolution: $preferredResolution based on memory class: $memoryClassMb MB"
        )

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    preferredResolution,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        // Set up camera
        cameraController = LifecycleCameraController(this).apply {
            cameraSelector = prefs.getPreferredCameraDirection()
            setImageCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            setTapToFocusEnabled(true)
            setPinchToZoomEnabled(true)

            // Configure image capture resolution
            setImageCaptureResolutionSelector(resolutionSelector)
        }

        // Attach it to the view
        binding.previewView.controller = cameraController
        cameraController.bindToLifecycle(this)

        // Wait for initialization to complete
        cameraController.initializationFuture.addListener({
            cameraInitialized = true
            updateFlipButtonVisibility()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateFlipButtonVisibility() {
        if (!::cameraController.isInitialized) return

        val hasFront = cameraController.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        val hasBack = cameraController.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)

        binding.cameraFlipButton.visibility =
            if (hasFront && hasBack) View.VISIBLE else View.GONE
    }

    private fun takePhoto() {
        val isFrontCamera = cameraController.cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA

        cameraController.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(img: ImageProxy) {
                    try {
                        val buffer = img.planes[0].buffer
                        val originalBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        val rotationDegrees = img.imageInfo.rotationDegrees
                        img.close()

                        val bitmap =
                            BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
                        var correctedBitmap = rotateBitmap(bitmap, rotationDegrees.toFloat())
                        if (isFrontCamera) {
                            correctedBitmap = mirrorBitmap(correctedBitmap)
                        }

                        val width = correctedBitmap.width
                        val height = correctedBitmap.height

                        val outputStream = ByteArrayOutputStream()
                        correctedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                        val compressedBytes = outputStream.toByteArray()

                        // Recycle bitmaps
                        bitmap.recycle()
                        if (correctedBitmap !== bitmap) correctedBitmap.recycle()

                        val uri = BlobUtils.getInstance()
                            .forData(compressedBytes)
                            .withMimeType(MediaTypes.IMAGE_JPEG)
                            .createForSingleSessionInMemory()

                        val data = Intent().apply {
                            data = uri
                            putExtra(EXTRA_IMAGE_URI, uri.toString())
                            putExtra(EXTRA_IMAGE_SIZE, compressedBytes.size.toLong())
                            putExtra(EXTRA_IMAGE_WIDTH, width)
                            putExtra(EXTRA_IMAGE_HEIGHT, height)
                        }

                        setResult(RESULT_OK, data)
                        finish()
                    } catch (t: Throwable) {
                        Log.e(TAG, "capture failed", t)
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    Log.e(TAG, "takePicture error", e)
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        )
    }

    private fun mirrorBitmap(src: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return src
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun flipCamera() {
        if (!::cameraController.isInitialized) return

        val newSelector =
            if (cameraController.cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else
                CameraSelector.DEFAULT_BACK_CAMERA

        cameraController.cameraSelector = newSelector
        prefs.setPreferredCameraDirection(newSelector)

        // Animate icon (simple 180° spin; no manual orientation tracking)
        binding.cameraFlipButton.animate()
            .rotationBy(-180f)
            .setDuration(200)
            .start()
    }

    private fun updateUiForRotation(rotation: Orientation = lastRotation) {
        val rotation =
            when (rotation) {
                Orientation.LANDSCAPE -> -90f
                Orientation.REVERSED_LANDSCAPE -> 90f
                else -> 0f
            }

        binding.cameraFlipButton.animate()
            .rotation(rotation)
            .setDuration(150)
            .start()
    }

    private fun initializeCountButton() {
        val count =  intent.getIntExtra(KEY_MEDIA_SEND_COUNT, 0)

            binding.mediasendCountContainer.mediasendCountButtonText.text = count.toString()
            binding.mediasendCountContainer.mediasendCountButton.isEnabled = count > 0
           binding.mediasendCountContainer.mediasendCountButton.visibility = if(count >0) View.VISIBLE else View.INVISIBLE
            if (count > 0) {
                binding.mediasendCountContainer.mediasendCountButton.setOnClickListener { v: View? ->
                   setResult(RESULT_CANCELED)
                    finish()
                }
            } else {
                binding.mediasendCountContainer.mediasendCountButton.setOnClickListener(null)
            }
    }

    private fun applyViewInsets(){
        binding.cameraCloseButton.applySafeInsetsPaddings()
        binding.root.applySafeInsetsPaddings(
            applyTop = false,
            applyBottom = true,
            consumeInsets = false
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (!::rootConstraintLayout.isInitialized) return

        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            landscapeConstraints.applyTo(rootConstraintLayout)
        } else {
            portraitConstraints.applyTo(rootConstraintLayout)
        }
        if (cameraInitialized) {
            updateFlipButtonVisibility()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orientationManager.startOrientationListener()
    }

    override fun onPause() {
        super.onPause()
        orientationManager.stopOrientationListener()
    }

    override fun onDestroy() {
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        super.onDestroy()

        orientationManager.destroy()
    }
}