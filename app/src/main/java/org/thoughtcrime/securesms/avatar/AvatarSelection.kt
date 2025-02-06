package org.thoughtcrime.securesms.avatar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import network.loki.messenger.R
import org.session.libsession.utilities.getColorFromAttr
import org.session.libsignal.utilities.ExternalStorageUtil.getImageDir
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.NoExternalStorageException
import org.thoughtcrime.securesms.util.FileProviderUtil
import java.io.File
import java.io.IOException
import java.util.LinkedList

class AvatarSelection(
    private val activity: Activity,
    private val onAvatarCropped: ActivityResultLauncher<CropImageContractOptions>,
    private val onPickImage: ActivityResultLauncher<Intent>
) {
    private val TAG: String = AvatarSelection::class.java.simpleName

    private val bgColor by lazy { activity.getColorFromAttr(android.R.attr.colorPrimary) }
    private val txtColor by lazy { activity.getColorFromAttr(android.R.attr.textColorPrimary) }
    private val imageScrim by lazy { ContextCompat.getColor(activity, R.color.avatar_background) }
    private val activityTitle by lazy { activity.getString(R.string.image) }

    /**
     * Returns result on [.REQUEST_CODE_CROP_IMAGE]
     */
    fun circularCropImage(
        inputFile: Uri?,
        outputFile: Uri?
    ) {
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