package org.thoughtcrime.securesms.mediasend

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.LifecycleCameraController
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.CameraxFragmentBinding
import org.session.libsession.utilities.MediaTypes
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.providers.BlobUtils
import org.thoughtcrime.securesms.util.applySafeInsetsMargins
import org.thoughtcrime.securesms.util.applySafeInsetsPaddings
import org.thoughtcrime.securesms.util.setSafeOnClickListener
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class CameraXFragment : Fragment() {

    interface Controller {
        fun onImageCaptured(imageUri: Uri, size: Long, width: Int, height: Int)
        fun onCameraError()
    }

    private lateinit var binding: CameraxFragmentBinding

    private var callbacks: Controller? = null

    private lateinit var cameraController: LifecycleCameraController
    private lateinit var cameraExecutor: ExecutorService


    private lateinit var orientationListener: OrientationEventListener
    private var lastRotation: Int = Surface.ROTATION_0

    private var cameraInitialized = false

    @Inject
    lateinit var prefs: TextSecurePreferences

    companion object {
        private const val TAG = "CameraXFragment"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val orientation = resources.configuration.orientation
        binding = inflateBindingForOrientation(inflater, container, orientation)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupUi(binding)
        // permissions should be handled prior to landing in this fragment
        // but this is added for safety
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(), REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // keep track of orientation changes
        orientationListener = object : OrientationEventListener(requireContext()) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return

                val newRotation = when {
                    degrees in  45..134  -> Surface.ROTATION_270
                    degrees in 135..224  -> Surface.ROTATION_180
                    degrees in 225..314  -> Surface.ROTATION_90
                    else                 -> Surface.ROTATION_0
                }

                if (newRotation != lastRotation) {
                    lastRotation = newRotation
                    updateUiForRotation(newRotation)
                }
            }
        }

        binding.root.applySafeInsetsPaddings(
            applyTop = false,
            applyBottom = false,
            consumeInsets = false
        )
    }

    /**
     * Wire up listeners and insets for a given binding.
     * This is reused on initial inflate and after reinflation on config changes.
     */
    private fun setupUi(binding: CameraxFragmentBinding) {
        binding.cameraControlsSafeArea.applySafeInsetsMargins()

        binding.cameraCaptureButton.setSafeOnClickListener { takePhoto() }
        binding.cameraFlipButton.setSafeOnClickListener { flipCamera() }
        binding.cameraCloseButton.setSafeOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
    }

    override fun onPause() {
        orientationListener.disable()
        super.onPause()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is Controller) {
            callbacks = context
        } else {
            throw RuntimeException("$context must implement CameraXFragment.Controller")
        }
    }

    private fun updateUiForRotation(rotation: Int = lastRotation) {
        val angle = when (rotation) {
            Surface.ROTATION_0 -> 0f
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_180 -> 180f
            else -> 270f
        }

        binding.cameraFlipButton.animate()
            .rotation(angle)
            .setDuration(150)
            .start()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            requireContext(), it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        // work out a resolution based on available memory
        val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryClassMb = activityManager.memoryClass  // e.g. 128, 256, etc.
        val preferredResolution: Size = when {
            memoryClassMb >= 256 -> Size(1920, 1440)
            memoryClassMb >= 128 -> Size(1280, 960)
            else -> Size(640, 480)
        }
        Log.d(TAG, "Selected resolution: $preferredResolution based on memory class: $memoryClassMb MB")

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    preferredResolution,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        // set up camera
        cameraController = LifecycleCameraController(requireContext()).apply {
            cameraSelector = prefs.getPreferredCameraDirection()
            setImageCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            setTapToFocusEnabled(true)
            setPinchToZoomEnabled(true)

            // Configure image capture resolution
            setImageCaptureResolutionSelector(resolutionSelector)
        }

        // attach it to the view
        binding.previewView.controller = cameraController
        cameraController.bindToLifecycle(viewLifecycleOwner)

        // wait for initialisation to complete
        cameraController.initializationFuture.addListener({
            cameraInitialized = true
            updateFlipButtonVisibility(binding)
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun updateFlipButtonVisibility(binding: CameraxFragmentBinding) {
        val hasFront = cameraController.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        val hasBack  = cameraController.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)

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

                        // Decode, rotate, mirror if needed
                        val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
                        var correctedBitmap = rotateBitmap(bitmap, rotationDegrees.toFloat())
                        if (isFrontCamera) {
                            correctedBitmap = mirrorBitmap(correctedBitmap)
                        }

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

                        callbacks?.onImageCaptured(uri, compressedBytes.size.toLong(), correctedBitmap.width, correctedBitmap.height)
                    } catch (t: Throwable) {
                        Log.e(TAG, "capture failed", t)
                        callbacks?.onCameraError()
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    Log.e(TAG, "takePicture error", e)
                    callbacks?.onCameraError()
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
        val newSelector =
            if (cameraController.cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else
                CameraSelector.DEFAULT_BACK_CAMERA

        cameraController.cameraSelector = newSelector
        prefs.setPreferredCameraDirection(newSelector)

        // animate icon
        binding.cameraFlipButton.animate()
            .rotationBy(-180f)
            .setDuration(200)
            .start()
    }

    // host activity will call this
    fun onHostConfigurationChanged(newConfig: Configuration) {
        // If the fragment's view/binding isn't ready, there's nothing to update
        if (!::binding.isInitialized) return

        val oldRoot = binding.root
        val parent = oldRoot.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(oldRoot)

        // Remove the old view from its parent
        parent.removeViewAt(index)

        // Inflate a new binding; Resources will choose layout or layout-land automatically
        val inflater = LayoutInflater.from(requireContext())
        val newBinding = inflateBindingForOrientation(inflater, parent, newConfig.orientation)

        // Update our binding reference
        binding = newBinding

        // Add the new root at the same index
        parent.addView(newBinding.root, index)

        // Re-wire UI and insets for the new binding
        setupUi(newBinding)
        newBinding.root.applySafeInsetsPaddings(
            applyTop = false,
            applyBottom = false,
            consumeInsets = false
        )

        // Re-attach the existing camera controller to the new PreviewView
        if (::cameraController.isInitialized) {
            newBinding.previewView.controller = cameraController

            if (cameraInitialized) {
                updateFlipButtonVisibility(newBinding)
            }
        }

        // No cameraController changes needed, it stays alive and PreviewView keeps working
    }

    private fun inflateBindingForOrientation(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        orientation: Int
    ): CameraxFragmentBinding {
        val layoutId = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            R.layout.camerax_fragment_landscape
        } else {
            R.layout.camerax_fragment_portrait
        }

        // Inflate the raw view, then bind it
        val root = inflater.inflate(layoutId, parent, false)
        return CameraxFragmentBinding.bind(root)
    }

    override fun onDestroyView() {
        cameraExecutor.shutdown()
        super.onDestroyView()
    }
}
