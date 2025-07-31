 package org.thoughtcrime.securesms.preferences

import android.Manifest
import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.getColorFromAttr
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.util.FileProviderUtil
import java.io.File
import javax.inject.Inject

 @AndroidEntryPoint
class SettingsActivity : FullComposeScreenLockActivity() {
    private val TAG = "SettingsActivity"

    @Inject
    lateinit var prefs: TextSecurePreferences

    private val viewModel: SettingsViewModel by viewModels()

    private val onAvatarCropped = registerForActivityResult(CropImageContract()) { result ->
        viewModel.onAvatarPicked(result)
    }

     private val pickPhotoLauncher: ActivityResultLauncher<PickVisualMediaRequest> =
         registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
             uri?.let {
                 viewModel.hideAvatarPickerOptions() // close the bottom sheet

                 // Handle the selected image URI
                 if(viewModel.isAnimated(uri)){ // no cropping for animated images
                     viewModel.onAvatarPicked(uri)
                 } else {
                     val outputFile = Uri.fromFile(File(cacheDir, "cropped"))
                     cropImage(it, outputFile)
                 }

             }
         }

     // Launcher for capturing a photo using the camera.
     private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
         if (success) {
             viewModel.hideAvatarPickerOptions() // close the bottom sheet

             val outputFile = Uri.fromFile(File(cacheDir, "cropped"))
             cropImage(viewModel.getTempFile()?.let(Uri::fromFile), outputFile)
         } else {
             Toast.makeText(this, R.string.errorUnknown, Toast.LENGTH_SHORT).show()
         }
     }

    private val bgColor by lazy { getColorFromAttr(android.R.attr.colorPrimary) }
    private val txtColor by lazy { getColorFromAttr(android.R.attr.textColorPrimary) }
    private val imageScrim by lazy { ContextCompat.getColor(this, R.color.avatar_background) }
    private val activityTitle by lazy { getString(R.string.image) }

    companion object {
        private const val SCROLL_STATE = "SCROLL_STATE"
    }

     @Composable
     override fun ComposeContent() {
         SettingsScreen(
             viewModel = viewModel,
             onGalleryPicked = {
                 try {
                     pickPhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                 } catch (e: ActivityNotFoundException) {
                     Toast.makeText(this, R.string.errorUnknown, Toast.LENGTH_SHORT).show()
                 }
             },
             onCameraPicked = {
                 viewModel.createTempFile()?.let{
                     takePhotoLauncher.launch(FileProviderUtil.getUriFor(this, it))
                 }
             },
             startAvatarSelection = this::startAvatarSelection,
             onBack = this::finish
         )
     }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_scale_in, R.anim.slide_to_bottom)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
    }

    private fun startAvatarSelection() {
        // Ask for an optional camera permission.
        Permissions.with(this)
            .request(Manifest.permission.CAMERA)
            .onAnyDenied {
                viewModel.showAvatarPickerOptions(showCamera = false)
            }
            .onAllGranted {
                viewModel.showAvatarPickerOptions(showCamera = true)
            }
            .execute()
    }

    private fun cropImage(inputFile: Uri?, outputFile: Uri?){
        onAvatarCropped.launch(
            CropImageContractOptions(
                uri = inputFile,
                cropImageOptions = CropImageOptions(
                    guidelines = CropImageView.Guidelines.ON,
                    aspectRatioX = 1,
                    aspectRatioY = 1,
                    fixAspectRatio = true,
                    cropShape = CropImageView.CropShape.OVAL,
                    customOutputUri = outputFile,
                    allowRotation = true,
                    allowFlipping = true,
                    backgroundColor = imageScrim,
                    toolbarColor = bgColor,
                    activityBackgroundColor = bgColor,
                    toolbarTintColor = txtColor,
                    toolbarBackButtonColor = txtColor,
                    toolbarTitleColor = txtColor,
                    activityMenuIconColor = txtColor,
                    activityMenuTextColor = txtColor,
                    activityTitle = activityTitle
                )
            )
        )
    }
}
