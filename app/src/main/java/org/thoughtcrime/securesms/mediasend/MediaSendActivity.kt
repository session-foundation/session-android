package org.thoughtcrime.securesms.mediasend

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.ScaleAnimation
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.view.ViewGroupCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.databinding.MediasendActivityBinding
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.MediaTypes
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.Util.isEmpty
import org.session.libsession.utilities.concurrent.SimpleTask
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import org.thoughtcrime.securesms.conversation.v2.utilities.AttachmentManager
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.mediasend.MediaSendViewModel.CountButtonState
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.scribbles.ImageEditorFragment
import org.thoughtcrime.securesms.util.FilenameUtils.constructPhotoFilename
import org.thoughtcrime.securesms.util.applySafeInsetsPaddings
import javax.inject.Inject

/**
 * Encompasses the entire flow of sending media, starting from the selection process to the actual
 * captioning and editing of the content.
 *
 * This activity is intended to be launched via [.startActivityForResult].
 * It will return the [Media] that the user decided to send.
 */
@AndroidEntryPoint
class MediaSendActivity : ScreenLockActionBarActivity(), MediaPickerFolderFragment.Controller,
    MediaPickerItemFragment.Controller, MediaSendFragment.Controller,
    ImageEditorFragment.Controller, CameraXFragment.Controller{
    private var recipient: Recipient? = null
    private val viewModel: MediaSendViewModel by viewModels()

    private lateinit var binding: MediasendActivityBinding

    @Inject
    lateinit var recipientRepository: RecipientRepository


    override val applyDefaultWindowInsets: Boolean
        get() = false // we want to handle window insets manually here for fullscreen fragments like the camera screen

    override val applyAutoScrimForNavigationBar: Boolean
        get() = false

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)

        binding = MediasendActivityBinding.inflate(layoutInflater).also {
            setContentView(it.root)
            ViewGroupCompat.installCompatInsetsDispatch(it.root)
        }

        setResult(RESULT_CANCELED)

        if (savedInstanceState != null) {
            return
        }

        // Apply windowInsets for our own UI (not the fragment ones because they will want to do their own things)
        binding.mediasendBottomBar.applySafeInsetsPaddings()

        recipient = recipientRepository.getRecipientSync(fromSerialized(
            intent.getStringExtra(KEY_ADDRESS)!!
        ))

        viewModel.onBodyChanged(intent.getStringExtra(KEY_BODY)!!)

        val media: List<Media?>? = intent.getParcelableArrayListExtra(KEY_MEDIA)
        val isCamera = intent.getBooleanExtra(KEY_IS_CAMERA, false)

        if (isCamera) {
            val fragment: Fragment = CameraXFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.mediasend_fragment_container, fragment, TAG_CAMERA)
                .commit()
        } else if (!isEmpty(media)) {
            viewModel.onSelectedMediaChanged(this, media!!)

            val fragment: Fragment = MediaSendFragment.newInstance(recipient!!.address)

            supportFragmentManager.beginTransaction()
                .replace(R.id.mediasend_fragment_container, fragment, TAG_SEND)
                .commit()
        } else {
            val fragment = MediaPickerFolderFragment.newInstance(
                recipient!!
            )
            supportFragmentManager.beginTransaction()
                .replace(R.id.mediasend_fragment_container, fragment, TAG_FOLDER_PICKER)
                .commit()
        }

        initializeCountButtonObserver()
        initializeCameraButtonObserver()
        initializeErrorObserver()

        binding.mediasendCameraButton.setOnClickListener { v: View? ->
            val maxSelection = MediaSendViewModel.MAX_SELECTED_FILES
            if (viewModel.getSelectedMedia().value != null && viewModel.getSelectedMedia().value!!.size >= maxSelection) {
                Toast.makeText(this, getString(R.string.attachmentsErrorNumber), Toast.LENGTH_SHORT)
                    .show()
            } else {
                navigateToCamera()
            }
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()

        if (intent.getBooleanExtra(
                KEY_IS_CAMERA,
                false
            ) && supportFragmentManager.backStackEntryCount == 0
        ) {
            viewModel.onImageCaptureUndo(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
    }

    override fun onFolderSelected(folder: MediaFolder) {
        viewModel.onFolderSelected(folder.bucketId)

        val fragment = MediaPickerItemFragment.newInstance(
            folder.bucketId,
            folder.title,
            MediaSendViewModel.MAX_SELECTED_FILES
        )
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_from_right,
                R.anim.slide_to_left,
                R.anim.slide_from_left,
                R.anim.slide_to_right
            )
            .replace(R.id.mediasend_fragment_container, fragment, TAG_ITEM_PICKER)
            .addToBackStack(null)
            .commit()
    }

    override fun onMediaSelected(media: Media) {
        try {
            viewModel.onSingleMediaSelected(this, media)
            navigateToMediaSend(recipient!!.address)
        } catch (e: Exception){
            Log.e(TAG, "Error selecting media", e)
            Toast.makeText(this, R.string.errorUnknown, Toast.LENGTH_LONG).show()
        }
    }

    override fun onAddMediaClicked(bucketId: String) {
        val folderFragment = MediaPickerFolderFragment.newInstance(
            recipient!!
        )
        val itemFragment =
            MediaPickerItemFragment.newInstance(bucketId, "", MediaSendViewModel.MAX_SELECTED_FILES)

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.stationary,
                R.anim.slide_to_left,
                R.anim.slide_from_left,
                R.anim.slide_to_right
            )
            .replace(R.id.mediasend_fragment_container, folderFragment, TAG_FOLDER_PICKER)
            .addToBackStack(null)
            .commit()

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_from_right,
                R.anim.stationary,
                R.anim.slide_from_left,
                R.anim.slide_to_right
            )
            .replace(R.id.mediasend_fragment_container, itemFragment, TAG_ITEM_PICKER)
            .addToBackStack(null)
            .commit()
    }

    override fun onSendClicked(media: List<Media>, message: String) {
        viewModel.onSendClicked()

        val mediaList = ArrayList(media)
        val intent = Intent()

        intent.putParcelableArrayListExtra(EXTRA_MEDIA, mediaList)
        intent.putExtra(EXTRA_MESSAGE, message)
        setResult(RESULT_OK, intent)
        finish()

        overridePendingTransition(R.anim.stationary, R.anim.camera_slide_to_bottom)
    }

    override fun onNoMediaAvailable() {
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onTouchEventsNeeded(needed: Boolean) {
        val fragment = supportFragmentManager.findFragmentByTag(TAG_SEND) as MediaSendFragment?
        fragment?.onTouchEventsNeeded(needed)
    }

    override fun onCameraError() {
        lifecycleScope.launch {
            Toast.makeText(applicationContext, R.string.cameraErrorUnavailable, Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED, Intent())
            finish()
        }
    }

    override fun onImageCaptured(imageUri: Uri, size: Long, width: Int, height: Int) {
        Log.i(TAG, "Camera image captured.")
        SimpleTask.run(lifecycle, {
            try {
                return@run Media(
                    imageUri,
                    constructPhotoFilename(this),
                    MediaTypes.IMAGE_JPEG,
                    System.currentTimeMillis(),
                    width,
                    height,
                    size,
                    Media.ALL_MEDIA_BUCKET_ID,
                    null
                )
            } catch (e: Exception) {
                return@run null
            }
        }, { media: Media? ->
            if (media == null) {
                onNoMediaAvailable()
                return@run
            }
            Log.i(TAG, "Camera capture stored: " + media.uri.toString())

            viewModel.onImageCaptured(media)
            navigateToMediaSend(recipient!!.address)
        })
    }

    private fun initializeCountButtonObserver() {
        viewModel.getCountButtonState().observe(
            this
        ) { buttonState: CountButtonState? ->
            if (buttonState == null) return@observe
            binding.mediasendCountButtonText.text = buttonState.count.toString()
            binding.mediasendCountButton.isEnabled = buttonState.isVisible
            animateButtonVisibility(
                binding.mediasendCountButton,
                binding.mediasendCountButton.visibility,
                if (buttonState.isVisible) View.VISIBLE else View.GONE
            )
            if (buttonState.count > 0) {
                binding.mediasendCountButton.setOnClickListener { v: View? ->
                    navigateToMediaSend(
                        recipient!!.address
                    )
                }
                if (buttonState.isVisible) {
                    animateButtonTextChange(binding.mediasendCountButton)
                }
            } else {
                binding.mediasendCountButton.setOnClickListener(null)
            }
        }
    }

    private fun initializeCameraButtonObserver() {
        viewModel.getCameraButtonVisibility().observe(
            this
        ) { visible: Boolean? ->
            if (visible == null) return@observe
            animateButtonVisibility(
                binding.mediasendCameraButton,
                binding.mediasendCameraButton.visibility,
                if (visible) View.VISIBLE else View.GONE
            )
        }
    }

    private fun initializeErrorObserver() {
        viewModel.getError().observe(
            this
        ) { error: MediaSendViewModel.Error? ->
            if (error == null) return@observe
            when (error) {
                MediaSendViewModel.Error.ITEM_TOO_LARGE -> Toast.makeText(
                    this,
                    R.string.attachmentsErrorSize,
                    Toast.LENGTH_LONG
                ).show()

                MediaSendViewModel.Error.TOO_MANY_ITEMS ->                     // In modern session we'll say you can't sent more than 32 items, but if we ever want
                    // the exact count of how many items the user attempted to send it's: viewModel.getMaxSelection()
                    Toast.makeText(
                        this,
                        getString(R.string.attachmentsErrorNumber),
                        Toast.LENGTH_SHORT
                    ).show()
            }
        }
    }

    private fun navigateToMediaSend(recipient: Address) {
        val fragment = MediaSendFragment.newInstance(recipient)
        var backstackTag: String? = null

        if (supportFragmentManager.findFragmentByTag(TAG_SEND) != null) {
            supportFragmentManager.popBackStack(TAG_SEND, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            backstackTag = TAG_SEND
        }

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_from_right,
                R.anim.slide_to_left,
                R.anim.slide_from_left,
                R.anim.slide_to_right
            )
            .replace(R.id.mediasend_fragment_container, fragment, TAG_SEND)
            .addToBackStack(backstackTag)
            .commit()
    }

    private fun navigateToCamera() {
        val c = applicationContext
        val permanentDenialTxt = Phrase.from(c, R.string.permissionsCameraDenied)
            .put(APP_NAME_KEY, c.getString(R.string.app_name))
            .format().toString()
        val requireCameraPermissionsTxt = Phrase.from(c, R.string.cameraGrantAccessDescription)
            .put(APP_NAME_KEY, c.getString(R.string.app_name))
            .format().toString()

        Permissions.with(this)
            .request(Manifest.permission.CAMERA)
            .withPermanentDenialDialog(permanentDenialTxt)
            .onAllGranted {
                val fragment = orCreateCameraFragment
                supportFragmentManager.beginTransaction()
                    .setCustomAnimations(
                        R.anim.slide_from_right,
                        R.anim.slide_to_left,
                        R.anim.slide_from_left,
                        R.anim.slide_to_right
                    )
                    .replace(
                        R.id.mediasend_fragment_container,
                        fragment,
                        TAG_CAMERA
                    )
                    .addToBackStack(null)
                    .commit()

                viewModel.onCameraStarted()
            }
            .onAnyDenied {
                Toast.makeText(
                    this@MediaSendActivity,
                    requireCameraPermissionsTxt,
                    Toast.LENGTH_LONG
                ).show()
            }
            .execute()
    }

    private val orCreateCameraFragment: CameraXFragment
        get() {
            val fragment =
                supportFragmentManager.findFragmentByTag(TAG_CAMERA) as CameraXFragment?

            return fragment ?: CameraXFragment()
        }

    private fun animateButtonVisibility(button: View, oldVisibility: Int, newVisibility: Int) {
        if (oldVisibility == newVisibility) return

        if (button.animation != null) {
            button.clearAnimation()
            button.visibility = newVisibility
        } else if (newVisibility == View.VISIBLE) {
            button.visibility = View.VISIBLE

            val animation: Animation = ScaleAnimation(
                0f,
                1f,
                0f,
                1f,
                Animation.RELATIVE_TO_SELF,
                0.5f,
                Animation.RELATIVE_TO_SELF,
                0.5f
            )
            animation.duration = 250
            animation.interpolator = OvershootInterpolator()
            button.startAnimation(animation)
        } else {
            val animation: Animation = ScaleAnimation(
                1f,
                0f,
                1f,
                0f,
                Animation.RELATIVE_TO_SELF,
                0.5f,
                Animation.RELATIVE_TO_SELF,
                0.5f
            )
            animation.duration = 150
            animation.interpolator = AccelerateDecelerateInterpolator()
            animation.setAnimationListener(object : SimpleAnimationListener() {
                override fun onAnimationEnd(animation: Animation) {
                    button.clearAnimation()
                    button.visibility = View.GONE
                }
            })

            button.startAnimation(animation)
        }
    }

    private fun animateButtonTextChange(button: View) {
        if (button.animation != null) {
            button.clearAnimation()
        }

        val grow: Animation = ScaleAnimation(
            1f,
            1.3f,
            1f,
            1.3f,
            Animation.RELATIVE_TO_SELF,
            0.5f,
            Animation.RELATIVE_TO_SELF,
            0.5f
        )
        grow.duration = 125
        grow.interpolator = AccelerateInterpolator()
        grow.setAnimationListener(object : SimpleAnimationListener() {
            override fun onAnimationEnd(animation: Animation) {
                val shrink: Animation = ScaleAnimation(
                    1.3f,
                    1f,
                    1.3f,
                    1f,
                    Animation.RELATIVE_TO_SELF,
                    0.5f,
                    Animation.RELATIVE_TO_SELF,
                    0.5f
                )
                shrink.duration = 125
                shrink.interpolator = DecelerateInterpolator()
                button.startAnimation(shrink)
            }
        })

        button.startAnimation(grow)
    }

    override fun onRequestFullScreen(fullScreen: Boolean) {
        val sendFragment = supportFragmentManager.findFragmentByTag(TAG_SEND) as MediaSendFragment?
        if (sendFragment != null && sendFragment.isVisible) {
            sendFragment.onRequestFullScreen(fullScreen)
        }
    }

    companion object {
        private val TAG: String = MediaSendActivity::class.java.simpleName

        const val EXTRA_MEDIA: String = "media"
        const val EXTRA_MESSAGE: String = "message"

        private const val KEY_ADDRESS = "address"
        private const val KEY_BODY = "body"
        private const val KEY_MEDIA = "media"
        private const val KEY_IS_CAMERA = "is_camera"

        private const val TAG_FOLDER_PICKER = "folder_picker"
        private const val TAG_ITEM_PICKER = "item_picker"
        private const val TAG_SEND = "send"
        private const val TAG_CAMERA = "camera"

        /**
         * Get an intent to launch the media send flow starting with the picker.
         */
        @JvmStatic
        fun buildGalleryIntent(context: Context, recipient: Address, body: String): Intent {
            val intent = Intent(context, MediaSendActivity::class.java)
            intent.putExtra(KEY_ADDRESS, recipient.toString())
            intent.putExtra(KEY_BODY, body)
            return intent
        }

        /**
         * Get an intent to launch the media send flow starting with the camera.
         */
        @JvmStatic
        fun buildCameraIntent(context: Context, recipient: Address, body: String): Intent {
            val intent = buildGalleryIntent(context, recipient, body)
            intent.putExtra(KEY_IS_CAMERA, true)
            return intent
        }

        /**
         * Get an intent to launch the media send flow with a specific list of media. Will jump right to
         * the editor screen.
         */
        fun buildEditorIntent(
            context: Context,
            media: List<Media>,
            recipient: Address,
            body: String
        ): Intent {
            val intent = buildGalleryIntent(context, recipient, body)
            intent.putParcelableArrayListExtra(KEY_MEDIA, ArrayList(media))
            return intent
        }
    }
}
