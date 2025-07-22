 package org.thoughtcrime.securesms.preferences

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import network.loki.messenger.databinding.ActivitySettingsBinding
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.NonTranslatableStringConstants.NETWORK_NAME
import org.session.libsession.utilities.SSKEnvironment.ProfileManagerProtocol
import org.session.libsession.utilities.StringSubstitutionConstants.VERSION_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.getColorFromAttr
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import org.thoughtcrime.securesms.debugmenu.DebugActivity
import org.thoughtcrime.securesms.home.PathActivity
import org.thoughtcrime.securesms.messagerequests.MessageRequestsActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.preferences.SettingsViewModel.AvatarDialogState.TempAvatar
import org.thoughtcrime.securesms.preferences.SettingsViewModel.AvatarDialogState.UserAvatar
import org.thoughtcrime.securesms.preferences.appearance.AppearanceSettingsActivity
import org.thoughtcrime.securesms.recoverypassword.RecoveryPasswordActivity
import org.thoughtcrime.securesms.reviews.InAppReviewManager
import org.thoughtcrime.securesms.tokenpage.TokenPageActivity
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.AnimatedSessionProActivatedCTA
import org.thoughtcrime.securesms.ui.AnimatedSessionProCTA
import org.thoughtcrime.securesms.ui.CTAFeature
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.Divider
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.LargeItemButton
import org.thoughtcrime.securesms.ui.LargeItemButtonWithDrawable
import org.thoughtcrime.securesms.ui.LoadingDialog
import org.thoughtcrime.securesms.ui.OpenURLAlertDialog
import org.thoughtcrime.securesms.ui.components.AcccentOutlineCopyButton
import org.thoughtcrime.securesms.ui.components.AccentOutlineButton
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.components.BaseBottomSheet
import org.thoughtcrime.securesms.ui.getCellBottomShape
import org.thoughtcrime.securesms.ui.getCellTopShape
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.setThemedContent
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.accentTextButtonColors
import org.thoughtcrime.securesms.ui.theme.dangerButtonColors
import org.thoughtcrime.securesms.util.FileProviderUtil
import org.thoughtcrime.securesms.util.applyCommonWindowInsetsOnViews
import org.thoughtcrime.securesms.util.push
import org.thoughtcrime.securesms.util.setSafeOnClickListener
import java.io.File
import javax.inject.Inject

 @AndroidEntryPoint
class SettingsActivity : ScreenLockActionBarActivity() {
    private val TAG = "SettingsActivity"

    @Inject
    lateinit var prefs: TextSecurePreferences

    @Inject
    lateinit var inAppReviewManager: InAppReviewManager

    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var binding: ActivitySettingsBinding
    private var displayNameEditActionMode: ActionMode? = null
        set(value) { field = value; handleDisplayNameEditActionModeChanged() }

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

    private val hideRecoveryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
     ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult

        if(result.data?.getBooleanExtra(RecoveryPasswordActivity.RESULT_RECOVERY_HIDDEN, false) == true){
            viewModel.permanentlyHidePassword()
        }
    }

    private val bgColor by lazy { getColorFromAttr(android.R.attr.colorPrimary) }
    private val txtColor by lazy { getColorFromAttr(android.R.attr.textColorPrimary) }
    private val imageScrim by lazy { ContextCompat.getColor(this, R.color.avatar_background) }
    private val activityTitle by lazy { getString(R.string.image) }

    companion object {
        private const val SCROLL_STATE = "SCROLL_STATE"
    }

     override val applyDefaultWindowInsets: Boolean
         get() = false

    // region Lifecycle
    override fun onCreate(savedInstanceState: Bundle?, isReady: Boolean) {
        super.onCreate(savedInstanceState, isReady)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // set the toolbar icon to a close icon
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_x)

        // set the compose dialog content
        binding.composeLayout.setThemedContent {
            val uiState by viewModel.uiState.collectAsState()
            SettingsComposeContent(
                uiState = uiState,
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
                }
            )
        }

        binding.run {
            userAvatar.setOnClickListener {
                viewModel.showAvatarDialog()
            }
            ctnGroupNameSection.setOnClickListener { startActionMode(DisplayNameEditActionModeCallback()) }
            btnGroupNameDisplay.text = viewModel.getDisplayName()
            publicKeyTextView.text = viewModel.hexEncodedPublicKey
            val gitCommitFirstSixChars = BuildConfig.GIT_HASH.take(6)
            val environment: String = if(BuildConfig.BUILD_TYPE == "release") "" else " - ${prefs.getEnvironment().label}"
            val versionDetails = " ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE} - $gitCommitFirstSixChars) $environment"
            val versionString = Phrase.from(applicationContext, R.string.updateVersion).put(VERSION_KEY, versionDetails).format()
            versionTextView.text = versionString
        }

        binding.composeView.setThemedContent {
            val uiState by viewModel.uiState.collectAsState()
            Buttons(recoveryHidden = uiState.recoveryHidden)
        }

        binding.userAvatar.setThemedContent {
            val uiState by viewModel.uiState.collectAsState()
            val avatarData = uiState.avatarData
            if(avatarData == null) return@setThemedContent

            Avatar(
                size = LocalDimensions.current.iconXXLarge,
                data = avatarData
            )
        }

        binding.sentLogoImageView.setSafeOnClickListener {
            viewModel.showUrlDialog("https://token.getsession.org")
        }

        applyCommonWindowInsetsOnViews(mainScrollView = binding.scrollView)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_scale_in, R.anim.slide_to_bottom)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val scrollBundle = SparseArray<Parcelable>()
        binding.scrollView.saveHierarchyState(scrollBundle)
        outState.putSparseParcelableArray(SCROLL_STATE, scrollBundle)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        savedInstanceState.getSparseParcelableArray<Parcelable>(SCROLL_STATE)?.let { scrollBundle ->
            binding.scrollView.restoreHierarchyState(scrollBundle)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.settings_general, menu)
        if (BuildConfig.BUILD_TYPE != "release") {
            menu.findItem(R.id.action_qr_code)?.contentDescription = resources.getString(R.string.AccessibilityId_qrView)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_qr_code -> {
                push<QRCodeActivity>()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
    }
    // endregion

    // region Updating
    private fun handleDisplayNameEditActionModeChanged() {
        val isEditingDisplayName = this.displayNameEditActionMode != null

        binding.btnGroupNameDisplay.isInvisible = isEditingDisplayName
        binding.displayNameEditText.isInvisible = !isEditingDisplayName

        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (isEditingDisplayName) {
            binding.displayNameEditText.setText(binding.btnGroupNameDisplay.text)
            binding.displayNameEditText.selectAll()
            binding.displayNameEditText.requestFocus()
            inputMethodManager.showSoftInput(binding.displayNameEditText, 0)

            // Save the updated display name when the user presses enter on the soft keyboard
            binding.displayNameEditText.setOnEditorActionListener { v, actionId, event ->
                when (actionId) {
                    // Note: IME_ACTION_DONE is how we've configured the soft keyboard to respond,
                    // while IME_ACTION_UNSPECIFIED is what triggers when we hit enter on a
                    // physical keyboard.
                    EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_UNSPECIFIED -> {
                        saveDisplayName()
                        displayNameEditActionMode?.finish()
                        true
                    }
                    else -> false
                }
            }
        } else {
            inputMethodManager.hideSoftInputFromWindow(binding.displayNameEditText.windowToken, 0)
        }
    }

    private fun updateDisplayName(displayName: String): Boolean {
        // We'll assume we fail & flip the flag on success
        var updateWasSuccessful = false

        val haveNetworkConnection = viewModel.hasNetworkConnection()
        if (!haveNetworkConnection) {
            Log.w(TAG, "Cannot update display name - no network connection.")
        } else {
            // if we have a network connection then attempt to update the display name
            TextSecurePreferences.setProfileName(this, displayName)
            viewModel.updateName(displayName)
            binding.btnGroupNameDisplay.text = displayName
            updateWasSuccessful = true
        }

        // Inform the user if we failed to update the display name
        if (!updateWasSuccessful) {
            Toast.makeText(this@SettingsActivity, R.string.profileErrorUpdate, Toast.LENGTH_LONG).show()
        }

        return updateWasSuccessful
    }
    // endregion

    // region Interaction

    /**
     * @return true if the update was successful.
     */
    private fun saveDisplayName(): Boolean {
        val displayName = binding.displayNameEditText.text.toString().trim()

        if (displayName.isEmpty()) {
            Toast.makeText(this, R.string.displayNameErrorDescription, Toast.LENGTH_SHORT).show()
            return false
        }

        if (displayName.toByteArray().size > ProfileManagerProtocol.NAME_PADDED_LENGTH) {
            Toast.makeText(this, R.string.displayNameErrorDescriptionShorter, Toast.LENGTH_SHORT).show()
            return false
        }

        return updateDisplayName(displayName)
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
    // endregion

    private inner class DisplayNameEditActionModeCallback: ActionMode.Callback {

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.title = getString(R.string.displayNameEnter)
            mode.menuInflater.inflate(R.menu.menu_apply, menu)

            menu.findItem(R.id.applyButton)?.let { menuItem ->
                val themeColor = getColorFromAttr(android.R.attr.textColorPrimary)
                menuItem.icon?.setTint(themeColor)
            }

            this@SettingsActivity.displayNameEditActionMode = mode
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            this@SettingsActivity.displayNameEditActionMode = null
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.applyButton -> {
                    if (this@SettingsActivity.saveDisplayName()) {
                        mode.finish()
                    }
                    return true
                }
            }
            return false
        }
    }

    @Composable
    fun Buttons(
        recoveryHidden: Boolean
    ) {
        val scope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .padding(horizontal = LocalDimensions.current.spacing)
        ) {
            Row(
                modifier = Modifier
                    .padding(top = LocalDimensions.current.xxsSpacing),
                horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing),
            ) {
                AccentOutlineButton(
                    stringResource(R.string.share),
                    modifier = Modifier.weight(1f),
                    onClick = ::sendInvitationToUseSession
                )

                AcccentOutlineCopyButton(
                    modifier = Modifier.weight(1f),
                    onClick = ::copyPublicKey,
                )
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            val hasPaths by OnionRequestAPI.hasPath.collectAsState()

            // Add the debug menu in non release builds
            if (BuildConfig.BUILD_TYPE != "release") {
                Cell{
                    LargeItemButton(
                        "Debug Menu",
                        R.drawable.ic_settings,
                        shape = getCellTopShape()
                    ) { push<DebugActivity>() }
                }

                Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))
            }

            Cell {
                Column {
                    // Donate
                    LargeItemButton(
                        textId = R.string.donate,
                        icon = R.drawable.ic_heart,
                        modifier = Modifier.qaTag(R.string.qa_settings_item_donate),
                        colors = accentTextButtonColors()
                    ) {
                        scope.launch {
                            inAppReviewManager.onEvent(InAppReviewManager.Event.DonateButtonClicked)
                        }
                        viewModel.showUrlDialog( "https://session.foundation/donate#app")
                    }
                    Divider()

                    // Invite a friend
                    LargeItemButton(
                        R.string.sessionInviteAFriend,
                        R.drawable.ic_user_round_plus,
                        Modifier.qaTag(R.string.AccessibilityId_sessionInviteAFriend)
                    ) { sendInvitationToUseSession() }
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))

            Cell {
                Column {
                    Crossfade(if (hasPaths) R.drawable.ic_status else R.drawable.ic_path_yellow, label = "path") {
                        LargeItemButtonWithDrawable(
                            R.string.onionRoutingPath,
                            it,
                            shape = if (BuildConfig.BUILD_TYPE != "release") RectangleShape
                            else getCellTopShape()
                        ) { push<PathActivity>() }
                    }
                    Divider()

                    // Add the token page option.
                    LargeItemButton(
                        modifier = Modifier.qaTag(R.string.qa_settings_item_session_network),
                        text = NETWORK_NAME,
                        icon = R.drawable.session_network_logo
                    ) { push<TokenPageActivity>() }
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))

            Cell {
                Column {
                    LargeItemButton(R.string.sessionPrivacy, R.drawable.ic_lock_keyhole, Modifier.qaTag(R.string.AccessibilityId_sessionPrivacy)) { push<PrivacySettingsActivity>() }
                    Divider()

                    LargeItemButton(R.string.sessionNotifications, R.drawable.ic_volume_2, Modifier.qaTag(R.string.AccessibilityId_notifications)) { push<NotificationSettingsActivity>() }
                    Divider()

                    LargeItemButton(R.string.sessionConversations, R.drawable.ic_users_round, Modifier.qaTag(R.string.AccessibilityId_sessionConversations)) { push<ChatSettingsActivity>() }
                    Divider()

                    LargeItemButton(R.string.sessionAppearance, R.drawable.ic_paintbrush_vertical, Modifier.qaTag(R.string.AccessibilityId_sessionAppearance)) { push<AppearanceSettingsActivity>() }
                    Divider()

                    LargeItemButton(R.string.sessionMessageRequests, R.drawable.ic_message_square_warning, Modifier.qaTag(R.string.AccessibilityId_sessionMessageRequests)) { push<MessageRequestsActivity>() }
                }
            }

            Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))

            Cell {
                Column {
                    // Only show the recovery password option if the user has not chosen to permanently hide it
                    if (!recoveryHidden) {
                        LargeItemButton(
                            R.string.sessionRecoveryPassword,
                            R.drawable.ic_recovery_password_custom,
                            Modifier.qaTag(R.string.AccessibilityId_sessionRecoveryPasswordMenuItem)
                        ) {
                            hideRecoveryLauncher.launch(Intent(baseContext, RecoveryPasswordActivity::class.java))
                            overridePendingTransition(R.anim.slide_from_right, R.anim.slide_to_left)
                        }
                        Divider()
                    }

                    LargeItemButton(R.string.sessionHelp, R.drawable.ic_question_custom, Modifier.qaTag(R.string.AccessibilityId_help)) { push<HelpSettingsActivity>() }
                    Divider()

                    LargeItemButton(
                        textId = R.string.sessionClearData,
                        icon = R.drawable.ic_trash_2,
                        modifier = Modifier.qaTag(R.string.AccessibilityId_sessionClearData),
                        colors = dangerButtonColors(),
                        shape = getCellBottomShape()
                    ) { ClearAllDataDialog().show(supportFragmentManager, "Clear All Data Dialog") }
                }
            }
        }
    }

    @Composable
    fun SettingsComposeContent(
        uiState: SettingsViewModel.UIState,
        onGalleryPicked: () -> Unit,
        onCameraPicked: () -> Unit
    ){
        // loading
        if(uiState.showLoader) {
            LoadingDialog()
        }

        // dialog for the avatar
        if(uiState.showAvatarDialog) {
            AvatarDialogContainer()
        }

        // Animated avatar CTA
        if(uiState.showAnimatedProCTA){
            AnimatedProCTA(
                isPro = uiState.isPro,
            )
        }

        // donate confirmation
        if(uiState.showUrlDialog != null){
            OpenURLAlertDialog(
                url = uiState.showUrlDialog,
                onDismissRequest = viewModel::hideUrlDialog
            )
        }

        // bottom sheets with options for avatar: Gallery or photo
        if(uiState.showAvatarPickerOptions) {
            AvatarBottomSheet(
                showCamera = uiState.showAvatarPickerOptionCamera,
                onDismissRequest = viewModel::hideAvatarPickerOptions,
                onGalleryPicked = onGalleryPicked,
                onCameraPicked = onCameraPicked
            )
        }
    }

    @Composable
    fun AvatarDialogContainer(){
        val state by viewModel.uiState.collectAsState()

        AvatarDialog(state = state)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun  AvatarBottomSheet(
        showCamera: Boolean,
        onDismissRequest: () -> Unit,
        onGalleryPicked: () -> Unit,
        onCameraPicked: () -> Unit
    ){
        BaseBottomSheet(
            sheetState = rememberModalBottomSheetState(),
            onDismissRequest = onDismissRequest
        ){
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = LocalDimensions.current.spacing)
                    .padding(bottom = LocalDimensions.current.spacing),
                horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.spacing)
            ) {
                AvatarOption(
                    modifier = Modifier.qaTag(R.string.AccessibilityId_imageButton),
                    title = stringResource(R.string.image),
                    iconRes = R.drawable.ic_image,
                    onClick = onGalleryPicked
                )

                if(showCamera) {
                    AvatarOption(
                        modifier = Modifier.qaTag(R.string.AccessibilityId_cameraButton),
                        title = stringResource(R.string.contentDescriptionCamera),
                        iconRes = R.drawable.ic_camera,
                        onClick = onCameraPicked
                    )
                }
            }
        }
    }

    @Composable
    fun AvatarOption(
        modifier: Modifier = Modifier,
        title: String,
        @DrawableRes iconRes: Int,
        onClick: () -> Unit
    ){
        Column(
            modifier = modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false),
                onClick = onClick
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                modifier = Modifier.size(LocalDimensions.current.iconXLarge)
                    .background(
                        shape = CircleShape,
                        color = LocalColors.current.backgroundBubbleReceived,
                    )
                    .padding(LocalDimensions.current.smallSpacing),
                painter = painterResource(id = iconRes),
                contentScale = ContentScale.Fit,
                contentDescription = null,
                colorFilter = ColorFilter.tint(LocalColors.current.textSecondary)
            )

            Text(
                modifier = Modifier.padding(top = LocalDimensions.current.xxsSpacing),
                text = title,
                style = LocalType.current.base,
                color = LocalColors.current.text
            )
        }
    }

    @OptIn(ExperimentalGlideComposeApi::class)
    @Composable
    fun AvatarDialog(
        state: SettingsViewModel.UIState,
    ){
        AlertDialog(
            onDismissRequest = {
                viewModel.onAvatarDialogDismissed()
            },
            title = stringResource(R.string.profileDisplayPictureSet),
            content = {
                // custom content that has the displayed images

                // animated Pro title
                if(state.isPostPro){
                    Row(
                        modifier = Modifier.padding(
                            top = LocalDimensions.current.xxxsSpacing,
                            bottom = LocalDimensions.current.xsSpacing,
                        )
                            .clickable{
                                viewModel.showAnimatedProCTA()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxxsSpacing)
                    ) {
                        if(state.isPro) {
                            Image(
                                modifier = Modifier.height(LocalType.current.base.lineHeight.value.dp),
                                painter = painterResource(id = R.drawable.ic_pro_badge),
                                contentDescription = NonTranslatableStringConstants.APP_PRO,
                            )

                            Text(
                                text = stringResource(R.string.proAnimatedDisplayPictureModalDescription),
                                style = LocalType.current.base.copy(color = LocalColors.current.textSecondary)
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.proAnimatedDisplayPicturesNonProModalDescription),
                                style = LocalType.current.base.copy(color = LocalColors.current.textSecondary)
                            )

                            Image(
                                modifier = Modifier.height(LocalType.current.base.lineHeight.value.dp),
                                painter = painterResource(id = R.drawable.ic_pro_badge),
                                contentDescription = NonTranslatableStringConstants.APP_PRO,
                            )
                        }
                    }
                }

                // main container that control the overall size and adds the rounded bg
                Box(
                    modifier = Modifier
                        .padding(vertical = LocalDimensions.current.smallSpacing)
                        .size(LocalDimensions.current.iconXXLarge)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null // the ripple doesn't look nice as a square with the plus icon on top too
                        ) {
                            startAvatarSelection()
                        }
                        .qaTag(R.string.AccessibilityId_avatarPicker)
                        .background(
                            shape = CircleShape,
                            color = LocalColors.current.backgroundBubbleReceived,
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // the image content will depend on state type
                    when(val s = state.avatarDialogState){
                        // user avatar
                        is UserAvatar -> {
                            Avatar(
                                size = LocalDimensions.current.iconXXLarge,
                                data = s.data
                            )
                        }

                        // temporary image
                        is TempAvatar -> {
                            GlideImage(
                                modifier = Modifier.size(LocalDimensions.current.iconXXLarge)
                                    .clip(shape = CircleShape,),
                                contentScale = ContentScale.Crop,
                                model = s.data,
                                contentDescription = stringResource(R.string.profileDisplayPicture)
                            )
                        }

                        // empty state
                        else -> {
                            Image(
                                modifier = Modifier.fillMaxSize()
                                    .padding(LocalDimensions.current.iconSmall)
                                    .align(Alignment.Center),
                                painter = painterResource(id = R.drawable.ic_image),
                                contentScale = ContentScale.Fit,
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(LocalColors.current.textSecondary)
                            )
                        }
                    }

                    // '+' button that sits atop the custom content
                    Image(
                        modifier = Modifier
                            .size(LocalDimensions.current.spacing)
                            .background(
                                shape = CircleShape,
                                color = LocalColors.current.accent
                            )
                            .padding(LocalDimensions.current.xxxsSpacing)
                            .align(Alignment.BottomEnd)
                        ,
                        painter = painterResource(id = R.drawable.ic_plus),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(Color.Black)
                    )
                }
            },
            showCloseButton = true, // display the 'x' button
            buttons = listOf(
                DialogButtonData(
                    text = GetString(R.string.save),
                    enabled = state.avatarDialogState is TempAvatar,
                    dismissOnClick = false,
                    onClick = viewModel::saveAvatar
                ),
                DialogButtonData(
                    text = GetString(if(state.avatarDialogState is TempAvatar) R.string.clear else R.string.remove),
                    color = LocalColors.current.danger,
                    enabled = state.avatarDialogState is UserAvatar || // can remove is the user has an avatar set
                            state.avatarDialogState is TempAvatar, // can clear a temp avatar
                    dismissOnClick = false,
                    onClick = viewModel::removeAvatar
                )
            )
        )
    }

     @Composable
     fun AnimatedProCTA(
         isPro: Boolean,
     ){
         if(isPro) {
             AnimatedSessionProActivatedCTA (
                 heroImageBg = R.drawable.cta_hero_animated_bg,
                 heroImageAnimatedFg = R.drawable.cta_hero_animated_fg,
                 text = stringResource(R.string.proAnimatedDisplayPicture),
                 onCancel = viewModel::hideAnimatedProCTA
             )
         } else {
             AnimatedSessionProCTA(
                 heroImageBg = R.drawable.cta_hero_animated_bg,
                 heroImageAnimatedFg = R.drawable.cta_hero_animated_fg,
                 text = stringResource(R.string.proAnimatedDisplayPictureCallToActionDescription),
                 features = listOf(
                     CTAFeature.Icon(stringResource(R.string.proFeatureListAnimatedDisplayPicture)),
                     CTAFeature.Icon(stringResource(R.string.proFeatureListLargerGroups)),
                     CTAFeature.RainbowIcon(stringResource(R.string.proFeatureListLoadsMore)),
                 ),
                 onUpgrade = viewModel::goToProUpgradeScreen,
                 onCancel = viewModel::hideAnimatedProCTA
             )
         }
     }

    @Preview
    @Composable
    fun PreviewAvatarDialog(
        @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
    ){
        PreviewTheme(colors) {
            AvatarDialog(
                state = SettingsViewModel.UIState(
                    recoveryHidden = false,
                    isPro = false,
                    isPostPro = false
                )
            )
        }
    }

    @Preview
    @Composable
    fun PreviewAvatarSheet(
        @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
    ){
        PreviewTheme(colors) {
            AvatarBottomSheet(
                showCamera = true,
                onDismissRequest = {},
                onGalleryPicked = {},
                onCameraPicked = {}
            )
        }
    }
}
