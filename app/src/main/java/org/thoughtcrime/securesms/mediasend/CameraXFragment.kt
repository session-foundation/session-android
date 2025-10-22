package org.thoughtcrime.securesms.mediasend

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import dagger.hilt.android.AndroidEntryPoint
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
import kotlin.math.roundToInt

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
        binding = CameraxFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        cameraExecutor = Executors.newSingleThreadExecutor()

        // permissions should be handled prior to landing in this fragment
        // but this is added for safety
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(), REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        binding.cameraControlsSafeArea.applySafeInsetsMargins()

        binding.cameraCaptureButton.setSafeOnClickListener { takePhoto() }
        binding.cameraFlipButton.setSafeOnClickListener { flipCamera() }
        binding.cameraCloseButton.setSafeOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
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
            Surface.ROTATION_0   -> 0f
            Surface.ROTATION_90  -> 90f
            Surface.ROTATION_180 -> 180f
            else                 -> 270f
        }

        if(!isAutoRotateOn()){
            binding.cameraFlipButton.animate()
                .rotation(angle)
                .setDuration(150)
                .start()
        }
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
            val hasFront = cameraController.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            val hasBack  = cameraController.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)

            binding.cameraFlipButton.visibility =
                if (hasFront && hasBack) View.VISIBLE else View.GONE
        }, ContextCompat.getMainExecutor(requireContext()))
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

    private fun isAutoRotateOn(): Boolean {
        return Settings.System.getInt(
            context?.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION, 0
        ) == 1
    }

    fun onHostConfigChanged() {
        applyLayoutForCurrentRotation()
    }

    private fun applyLayoutForCurrentRotation() {
        // 1) Make sure the overlay is truly full screen
        constrainOverlayToParent()

        // 2) Flip the preview ratio based on rotation
        val rot = requireView().display?.rotation ?: Surface.ROTATION_0
        val isLandscape = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
        applyPreviewConstraints(isLandscape)

        // 3) Move controls where we want them
        applyControlsConstraints(isLandscape)

        // 4) Keep icons upright (no animations)
        rotateIconsUpright(rot)
    }

    /** Ensure camera_controls_safe_area fills the root and is on top */
    private fun constrainOverlayToParent() {
        val root = binding.root as ConstraintLayout
        val set = ConstraintSet().apply { clone(root) }
        val id = binding.cameraControlsSafeArea.id

        set.clear(id)
        set.connect(id, ConstraintSet.TOP,    ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        set.connect(id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        set.connect(id, ConstraintSet.START,  ConstraintSet.PARENT_ID, ConstraintSet.START)
        set.connect(id, ConstraintSet.END,    ConstraintSet.PARENT_ID, ConstraintSet.END)
        set.constrainWidth(id,  ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(id, ConstraintSet.MATCH_CONSTRAINT)
        set.applyTo(root)

        // kill any accidental rotation/translation that could push it off-screen
        binding.cameraControlsSafeArea.apply {
            rotation = 0f
            translationX = 0f
            translationY = 0f
            bringToFront()
            visibility = View.VISIBLE
        }
    }

    private fun applyPreviewConstraints(isLandscape: Boolean) {
        val root = binding.root as ConstraintLayout
        val set = ConstraintSet().apply { clone(root) }
        val previewId = binding.previewView.id

        set.clear(previewId)
        set.connect(previewId, ConstraintSet.TOP,    ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        set.connect(previewId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        set.connect(previewId, ConstraintSet.START,  ConstraintSet.PARENT_ID, ConstraintSet.START)
        set.connect(previewId, ConstraintSet.END,    ConstraintSet.PARENT_ID, ConstraintSet.END)
        set.constrainWidth(previewId,  ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(previewId, ConstraintSet.MATCH_CONSTRAINT)
        set.setDimensionRatio(previewId, if (isLandscape) "4:3" else "3:4")
        set.applyTo(root)
    }

    private fun applyControlsConstraints(isLandscape: Boolean) {
        if(!isLandscape) return
        val controls = binding.cameraControlsSafeArea as ConstraintLayout
        val captureId = binding.cameraCaptureButton.id
        val closeId   = binding.cameraCloseButton.id
        val flipId    = binding.cameraFlipButton.id
        val closeLp   = binding.cameraCloseButton.layoutParams as ViewGroup.MarginLayoutParams
        val captureLp = binding.cameraCaptureButton.layoutParams as ViewGroup.MarginLayoutParams
        val forty     = dp(binding, 40)

        ConstraintSet().apply {
            clone(controls)

            // make sure they stay visible even if XML had them GONE
            setVisibility(closeId, View.VISIBLE)
            setVisibility(flipId,  View.VISIBLE)

            // CLOSE: top-start with existing margins
            clear(closeId)
            connect(closeId, ConstraintSet.TOP,   ConstraintSet.PARENT_ID, ConstraintSet.TOP,   closeLp.topMargin)
            connect(closeId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, closeLp.marginStart)
            constrainWidth(closeId,  ConstraintSet.WRAP_CONTENT)
            constrainHeight(closeId, ConstraintSet.WRAP_CONTENT)

            // CAPTURE: fixed 80dp if not measured yet, centered vertically at end
            clear(captureId)
            constrainWidth(captureId,  binding.cameraCaptureButton.width.takeIf { it > 0 } ?: dp(binding, 80))
            constrainHeight(captureId, binding.cameraCaptureButton.height.takeIf { it > 0 } ?: dp(binding, 80))
            connect(captureId, ConstraintSet.TOP,    ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(captureId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, captureLp.bottomMargin)
            connect(captureId, ConstraintSet.END,    ConstraintSet.PARENT_ID, ConstraintSet.END)

            // FLIP: ***under*** the capture with 40dp, centered on capture, keep WRAP_CONTENT
            clear(flipId)
            constrainWidth(flipId,  ConstraintSet.WRAP_CONTENT)
            constrainHeight(flipId, ConstraintSet.WRAP_CONTENT)
            connect(flipId, ConstraintSet.TOP,  captureId, ConstraintSet.BOTTOM, forty)
            connect(flipId, ConstraintSet.START, captureId, ConstraintSet.START)
            connect(flipId, ConstraintSet.END,   captureId, ConstraintSet.END)
            setHorizontalBias(flipId, 0.5f) // center within capture’s left/right

            applyTo(controls)
        }


//        val root = binding.root as ConstraintLayout
//        val controls = binding.cameraControlsSafeArea as ConstraintLayout
//
//        // PreviewView constraints
//        ConstraintSet().apply {
//            clone(root)
//            clear(binding.previewView.id)
//            constrainWidth(binding.previewView.id, 0)
//            constrainHeight(binding.previewView.id, ConstraintSet.MATCH_CONSTRAINT)
//            setDimensionRatio(binding.previewView.id, "4:3")
//            connect(binding.previewView.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
//            connect(binding.previewView.id, ConstraintSet.END,   ConstraintSet.PARENT_ID, ConstraintSet.END)
//            connect(binding.previewView.id, ConstraintSet.TOP,   ConstraintSet.PARENT_ID, ConstraintSet.TOP)
//            connect(binding.previewView.id, ConstraintSet.BOTTOM,ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
//            applyTo(root)
//        }
//
//        // Controls constraints
//        ConstraintSet().apply {
//            clone(controls)
//
//            // reuse the margins already defined in XML (no R lookups)
//            val closeLp = binding.cameraCloseButton.layoutParams as ViewGroup.MarginLayoutParams
//            val captureLp = binding.cameraCaptureButton.layoutParams as ViewGroup.MarginLayoutParams
//            val forty = dp(binding, 40)
//
//            // Make sure ConstraintSet keeps them visible
//            setVisibility(binding.cameraCloseButton.id, View.VISIBLE)
//            setVisibility(binding.cameraFlipButton.id, View.VISIBLE)
//
//            // close button
//            clear(binding.cameraCloseButton.id)
//            connect(binding.cameraCloseButton.id, ConstraintSet.TOP,    ConstraintSet.PARENT_ID, ConstraintSet.TOP, closeLp.topMargin)
//            connect(binding.cameraCloseButton.id, ConstraintSet.START,  ConstraintSet.PARENT_ID, ConstraintSet.START, closeLp.marginStart)
//
//            // capture button
//            clear(binding.cameraCaptureButton.id)
//            constrainWidth(binding.cameraCaptureButton.id, binding.cameraCaptureButton.width.takeIf { it > 0 } ?: dp(binding, 80))
//            constrainHeight(binding.cameraCaptureButton.id, binding.cameraCaptureButton.height.takeIf { it > 0 } ?: dp(binding, 80))
//            connect(binding.cameraCaptureButton.id, ConstraintSet.TOP,    ConstraintSet.PARENT_ID, ConstraintSet.TOP)
//            connect(binding.cameraCaptureButton.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, captureLp.bottomMargin)
//            connect(binding.cameraCaptureButton.id, ConstraintSet.END,    ConstraintSet.PARENT_ID, ConstraintSet.END)
//
//            // flip button
//            clear(binding.cameraFlipButton.id)
//            connect(binding.cameraFlipButton.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
//            connect(binding.cameraFlipButton.id, ConstraintSet.START, binding.cameraCaptureButton.id, ConstraintSet.START)
//            connect(binding.cameraFlipButton.id, ConstraintSet.END,   binding.cameraCaptureButton.id, ConstraintSet.END)
//
//            applyTo(controls)
//        }
    }

    /** Keep icons upright; instant change (no animation) */
    private fun rotateIconsUpright(rotation: Int) {
        val angle = when (rotation) {
            Surface.ROTATION_0   -> 0f
            Surface.ROTATION_90  -> -90f
            Surface.ROTATION_180 -> 180f
            else                 -> 90f
        }
        listOf(
            binding.cameraCaptureButton,
            binding.cameraFlipButton,
            binding.cameraCloseButton
        ).forEach { v ->
            v.animate().cancel()
            v.rotation = angle
        }
    }

    private fun dp(binding: Any, value: Int): Int {
        val dm = (binding as? ViewBinding)?.root?.resources?.displayMetrics
        return (value * (dm?.density ?: 1f)).roundToInt()
    }

    override fun onDestroyView() {
        cameraExecutor.shutdown()
        super.onDestroyView()
    }
}
