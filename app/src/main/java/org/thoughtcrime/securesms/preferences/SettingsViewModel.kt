package org.thoughtcrime.securesms.preferences

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canhub.cropper.CropImage
import com.canhub.cropper.CropImageView
import com.squareup.phrase.Phrase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.UserPic
import okio.blackholeSink
import okio.buffer
import okio.source
import org.session.libsession.database.StorageProtocol
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.StringSubstitutionConstants.VERSION_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.displayName
import org.session.libsignal.utilities.ExternalStorageUtil.getImageDir
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.NoExternalStorageException
import org.thoughtcrime.securesms.attachments.AttachmentProcessor
import org.thoughtcrime.securesms.attachments.AvatarUploadManager
import org.thoughtcrime.securesms.conversation.v2.utilities.TextUtilities.textSizeInBytes
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.mms.MediaConstraints
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.pro.ProDataState
import org.thoughtcrime.securesms.pro.ProDetailsRepository
import org.thoughtcrime.securesms.pro.getDefaultSubscriptionStateData
import org.thoughtcrime.securesms.reviews.InAppReviewManager
import org.thoughtcrime.securesms.ui.SimpleDialogData
import org.thoughtcrime.securesms.util.AnimatedImageUtils
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.ClearDataUtils
import org.thoughtcrime.securesms.util.DonationManager.Companion.URL_DONATE
import org.thoughtcrime.securesms.util.NetworkConnectivity
import org.thoughtcrime.securesms.util.mapToStateFlow
import java.io.File
import java.io.IOException
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: TextSecurePreferences,
    private val configFactory: ConfigFactory,
    private val connectivity: NetworkConnectivity,
    private val avatarUtils: AvatarUtils,
    private val recipientRepository: RecipientRepository,
    private val proStatusManager: ProStatusManager,
    private val clearDataUtils: ClearDataUtils,
    private val storage: StorageProtocol,
    private val inAppReviewManager: InAppReviewManager,
    private val avatarUploadManager: AvatarUploadManager,
    private val attachmentProcessor: AttachmentProcessor,
    val proDetailsRepository: ProDetailsRepository,
) : ViewModel() {
    private val TAG = "SettingsViewModel"

    private var tempFile: File? = null

    private val selfRecipient: StateFlow<Recipient> = recipientRepository.observeSelf()
        .mapToStateFlow(viewModelScope, recipientRepository.getSelf(), valueGetter = { it })

    private val _uiState = MutableStateFlow(UIState(
        username = "",
        accountID = selfRecipient.value.address.address,
        hasPath = true,
        version = getVersionNumber(),
        recoveryHidden = prefs.getHidePassword(),
        isPostPro = proStatusManager.isPostPro(),
        proDataState = getDefaultSubscriptionStateData(),
    ))
    val uiState: StateFlow<UIState>
        get() = _uiState

    init {
        // observe current user
        viewModelScope.launch {
            selfRecipient
                .collectLatest { recipient ->
                    _uiState.update {
                        it.copy(
                            username = recipient.displayName(attachesBlindedId = false),
                        )
                    }
                }
        }

        // observe subscription status
        viewModelScope.launch {
            proStatusManager.proDataState.collect { state ->
                _uiState.update { it.copy(proDataState = state) }
            }
        }

        // set default dialog ui
        viewModelScope.launch {
            _uiState.update { it.copy(avatarDialogState = getDefaultAvatarDialogState()) }
        }

        viewModelScope.launch {
            proStatusManager.postProLaunchStatus.collect { postPro ->
                _uiState.update { it.copy(isPostPro = postPro) }
            }
        }

        viewModelScope.launch {
            prefs.watchHidePassword().collect { hidden ->
                _uiState.update { it.copy(recoveryHidden = hidden) }
            }
        }

        viewModelScope.launch {
            OnionRequestAPI.hasPath.collect { data ->
                _uiState.update { it.copy(hasPath = data) }
            }
        }

        viewModelScope.launch {
            selfRecipient
                .map(avatarUtils::getUIDataFromRecipient)
                .distinctUntilChanged()
                .collectLatest { data ->
                    _uiState.update { it.copy(avatarData = data) }
                }
        }

        viewModelScope.launch {
            proDetailsRepository.requestRefresh()
        }
    }

    private fun getVersionNumber(): CharSequence {
        val gitCommitFirstSixChars = BuildConfig.GIT_HASH.take(6)
        val environment: String = if(BuildConfig.BUILD_TYPE == "release") "" else " - ${prefs.getEnvironment().label}"
        val versionDetails = " ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE} - $gitCommitFirstSixChars) $environment"
        return Phrase.from(context, R.string.updateVersion).put(VERSION_KEY, versionDetails).format()
    }

    fun hasAvatar() = selfRecipient.value.avatar != null

    fun createTempFile(): File? {
        try {
            tempFile = File.createTempFile("avatar-capture", ".jpg", getImageDir(context))
        } catch (e: IOException) {
            Log.e("Cannot reserve a temporary avatar capture file.", e)
        } catch (e: NoExternalStorageException) {
            Log.e("Cannot reserve a temporary avatar capture file.", e)
        }

        return tempFile
    }

    fun getTempFile() = tempFile

    fun onAvatarPicked(result: CropImageView.CropResult) {
        when {
            result.isSuccessful -> {
                onAvatarPicked("file://${result.getUriFilePath(context)!!}".toUri())
            }

            result is CropImage.CancelledResult -> {
                Log.i(TAG, "Cropping image was cancelled by the user")
            }

            else -> {
                Log.e(TAG, "Cropping image failed")
            }
        }
    }

    fun onAvatarPicked(uri: Uri) {
        Log.i(TAG,  "Picked a new avatar: $uri")

        viewModelScope.launch {
            try {
                // Query the content resolver for the size of this image
                val contentSize = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                                cursor.getLong(sizeIndex)
                            } else {
                                null
                            }
                        }
                        ?: context.contentResolver.openInputStream(uri)!!.source().buffer().use { it.readAll(blackholeSink()) }
                }

                if (contentSize > MediaConstraints.getPushMediaConstraints().getImageMaxSize(context).toLong()) {
                    Log.e(TAG, "Selected avatar image is too large: $contentSize bytes")
                    Toast.makeText(context, R.string.profileDisplayPictureSizeError, Toast.LENGTH_LONG).show()
                    return@launch
                }


                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use {
                        it.readBytes()
                    }
                }

                _uiState.update {
                    it.copy(
                        avatarDialogState = AvatarDialogState.TempAvatar(
                            data = bytes,
                            isAnimated = isAnimated(bytes),
                            hasAvatar = hasAvatar()
                        )
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error reading avatar bytes", e)
                Toast.makeText(context, R.string.profileErrorUpdate, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    fun onAvatarDialogDismissed() {
        viewModelScope.launch {
            _uiState.update { it.copy(
                avatarDialogState = getDefaultAvatarDialogState(),
                showAvatarDialog = false
            ) } }
    }

    private fun getDefaultAvatarDialogState() = if (hasAvatar()) AvatarDialogState.UserAvatar(
        avatarUtils.getUIDataFromRecipient(selfRecipient.value)
    )
    else AvatarDialogState.NoAvatar

    private fun saveAvatar() {
        val tempAvatar = (uiState.value.avatarDialogState as? AvatarDialogState.TempAvatar)
            ?: return Toast.makeText(context, R.string.profileErrorUpdate, Toast.LENGTH_LONG).show()

        // if the selected avatar is animated but the user isn't pro, show the animated pro CTA
        if (tempAvatar.isAnimated && !selfRecipient.value.isPro && proStatusManager.isPostPro()) {
            showAnimatedProCTA()
            return
        }

        // dismiss avatar dialog
        // we don't want to do it earlier as the animated / pro case above should not close the dialog
        // to give the user a chance ti pick something else
        onAvatarDialogDismissed()

        if (!hasNetworkConnection()) {
            Log.w(TAG, "Cannot update profile picture - no network connection.")
            Toast.makeText(context, R.string.profileErrorUpdate, Toast.LENGTH_LONG).show()
            return
        }

        val onFail: () -> Unit = {
            Log.e(TAG, "Sync failed when uploading profile picture.")
            Toast.makeText(context, R.string.profileErrorUpdate, Toast.LENGTH_LONG).show()
        }

        syncProfilePicture(tempAvatar.data, onFail)
    }


    private fun removeAvatar() {
        // if the user has a temporary avatar selected, clear that and redisplay the default avatar instead
        if (uiState.value.avatarDialogState is AvatarDialogState.TempAvatar) {
            viewModelScope.launch {
                _uiState.update { it.copy(avatarDialogState = getDefaultAvatarDialogState()) }
            }
            return
        }

        onAvatarDialogDismissed()

        // otherwise this action is for removing the existing avatar
        val haveNetworkConnection = connectivity.networkAvailable.value
        if (!haveNetworkConnection) {
            Log.w(TAG, "Cannot remove profile picture - no network connection.")
            Toast.makeText(context, R.string.profileDisplayPictureRemoveError, Toast.LENGTH_LONG).show()
            return
        }

        val onFail: () -> Unit = {
            Log.e(TAG, "Sync failed when removing profile picture.")
            Toast.makeText(context, R.string.profileDisplayPictureRemoveError, Toast.LENGTH_LONG).show()
        }

        val emptyProfilePicture = ByteArray(0)
        syncProfilePicture(emptyProfilePicture, onFail)
    }

    // Helper method used by updateProfilePicture and removeProfilePicture to sync it online
    private fun syncProfilePicture(profilePicture: ByteArray, onFail: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(showLoader = true) }

            try {
                if (profilePicture.isEmpty()) {
                    configFactory.withMutableUserConfigs {
                        it.userProfile.setPic(UserPic.DEFAULT)
                    }

                    // update dialog state
                    _uiState.update { it.copy(avatarDialogState = AvatarDialogState.NoAvatar) }
                } else {
                    val processed = withContext(Dispatchers.Default) {
                        attachmentProcessor.processAvatar(profilePicture)
                    }?.data ?: profilePicture

                    avatarUploadManager.uploadAvatar(processed, isReupload = false)

                    // We'll have to refetch the recipient to get the new avatar
                    val selfRecipient = recipientRepository.getSelf()

                    // update dialog state
                    _uiState.update { it.copy(avatarDialogState = AvatarDialogState.UserAvatar(avatarUtils.getUIDataFromRecipient(selfRecipient))) }
                }

            } catch (e: Exception){ // If the sync failed then inform the user
                Log.d(TAG, "Error syncing avatar", e)
                onFail()
            }

            // And remove the loader animation after we've waited for the attempt to succeed or fail
            _uiState.update { it.copy(showLoader = false) }
        }
    }

    fun hasNetworkConnection(): Boolean = connectivity.networkAvailable.value

    fun isAnimated(uri: Uri) = proStatusManager.isPostPro() // block animated avatars prior to pro
            && AnimatedImageUtils.isAnimated(context, uri)

    fun isAnimated(rawImageData: ByteArray) = proStatusManager.isPostPro() // block animated avatars prior to pro
            && AnimatedImageUtils.isAnimated(rawImageData)

    private fun showAnimatedProCTA() {
        _uiState.update { it.copy(showAnimatedProCTA = true) }
    }

    private fun hideAnimatedProCTA() {
        _uiState.update { it.copy(showAnimatedProCTA = false) }
    }

    fun showAvatarDialog() {
        _uiState.update { it.copy(showAvatarDialog = true) }
    }

    fun hideAvatarPickerOptions() {
        _uiState.update { it.copy(showAvatarPickerOptions = false) }

    }

    fun showUrlDialog(url: String) {
        _uiState.update { it.copy(showUrlDialog = url) }
    }

    fun showAvatarPickerOptions(showCamera: Boolean) {
        _uiState.update { it.copy(
            showAvatarPickerOptions = true,
            showAvatarPickerOptionCamera = showCamera
        ) }
    }

    private fun clearData(clearNetwork: Boolean) {
        val currentClearState = uiState.value.clearDataDialog
        val isPro = selfRecipient.value.isPro
        // show loading
        _uiState.update { it.copy(clearDataDialog = ClearDataState.Clearing) }

        // only clear locally is clearNetwork is false or we are in an error state
        viewModelScope.launch(Dispatchers.Default) {
            when{
                // we have already confirmed the deletion
                currentClearState is ClearDataState.ConfirmedClearDataState -> {
                    if(clearNetwork){
                        clearDataDeviceAndNetwork()
                    } else {
                        clearDataDeviceOnly()
                    }
                }

                // we need special confirmations for pro users
                isPro -> {
                    if(!clearNetwork || currentClearState == ClearDataState.Error){
                        _uiState.update { it.copy(clearDataDialog = ClearDataState.ConfirmedClearDataState.ConfirmDevicePro) }
                    } else {
                        _uiState.update { it.copy(clearDataDialog = ClearDataState.ConfirmedClearDataState.ConfirmNetworkPro) }
                    }
                }

                else -> {
                    if(!clearNetwork || currentClearState == ClearDataState.Error){
                        clearDataDeviceOnly()
                    } else {
                        _uiState.update { it.copy(clearDataDialog = ClearDataState.ConfirmedClearDataState.ConfirmNetwork) }
                    }
                }
            }
        }
    }

    private suspend fun clearDataDeviceOnly() {
        val result = runCatching {
            clearDataUtils.clearAllDataAndRestart()
        }

        withContext(Main) {
            if (result.isSuccess) {
                _uiState.update { it.copy(clearDataDialog = ClearDataState.Hidden) }
            } else {
                Toast.makeText(context, R.string.errorUnknown, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun clearDataDeviceAndNetwork() {
        val deletionResultMap: Map<String, Boolean>? = try {
            val allCommunityServers = configFactory.withUserConfigs { it.userGroups.allCommunityInfo() }
                .mapTo(hashSetOf()) {  it.community.baseUrl }

            coroutineScope {
                allCommunityServers.map { server ->
                    launch {
                        runCatching { OpenGroupApi.deleteAllInboxMessages(server) }
                            .onFailure { Log.e(TAG, "Error deleting messages for $server", it) }
                    }
                }.joinAll()
            }

            SnodeAPI.deleteAllMessages(checkNotNull(storage.userAuth)).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete network messages - offering user option to delete local data only.", e)
            null
        }

        // If one or more deletions failed then inform the user and allow them to clear the device only if they wish..
        if (deletionResultMap == null || deletionResultMap.values.any { !it } || deletionResultMap.isEmpty()) {
            withContext(Main) {
                _uiState.update { it.copy(clearDataDialog = ClearDataState.Error) }
            }
        }
        else if (deletionResultMap.values.all { it }) {
            // ..otherwise if the network data deletion was successful proceed to delete the local data as well.
            clearDataDeviceOnly()
        }
    }

    private fun setUsername(name: String){
        if(!hasNetworkConnection()){
            Log.w(TAG, "Cannot update display name - no network connection.")
            Toast.makeText(context, R.string.profileErrorUpdate, Toast.LENGTH_LONG).show()
            return
        }

        // save username
        _uiState.update { it.copy(username = name) }
        configFactory.withMutableUserConfigs {
            it.userProfile.setName(name)
        }
    }

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.ShowClearDataDialog -> {
                _uiState.update { it.copy(clearDataDialog = ClearDataState.Default) }
            }

            is Commands.HideClearDataDialog -> {
                _uiState.update { it.copy(clearDataDialog = ClearDataState.Hidden) }
            }

            is Commands.ShowUrlDialog -> {
                showUrlDialog(command.url)
            }

            is Commands.HideUrlDialog -> {
                _uiState.update { it.copy(showUrlDialog = null) }
            }

            is Commands.ShowAvatarDialog -> {
                showAvatarDialog()
            }

            is Commands.ShowAvatarPickerOptions -> {
                showAvatarPickerOptions(command.showCamera)
            }

            is Commands.HideAvatarPickerOptions -> {
                hideAvatarPickerOptions()
            }

            is Commands.OnAvatarDialogDismissed -> {
                onAvatarDialogDismissed()
            }

            is Commands.ShowAnimatedProCTA -> {
                showAnimatedProCTA()
            }

            is Commands.HideAnimatedProCTA -> {
               hideAnimatedProCTA()
            }

            is Commands.SaveAvatar -> {
                saveAvatar()
            }

            is Commands.RemoveAvatar -> {
                removeAvatar()
            }

            is Commands.ClearData -> {
                clearData(command.clearNetwork)
            }

            is Commands.ShowUsernameDialog -> {
                _uiState.update {
                    it.copy(usernameDialog = UsernameDialogData(
                        currentName = it.username,
                        inputName = it.username,
                        setEnabled = false,
                        error = null
                    ))
                }
            }


            is Commands.HideUsernameDialog -> {
                _uiState.update { it.copy(usernameDialog = null) }
            }

            is Commands.SetUsername -> {
                _uiState.value.usernameDialog?.inputName?.trim()?.let {
                    setUsername(it)
                }

                // hide username dialog
                _uiState.update { it.copy(usernameDialog = null) }
            }

            is Commands.UpdateUsername -> {
                val trimmedName = command.name.trim()

                val error: String? = when {
                    trimmedName.textSizeInBytes() > 100 ->
                        context.getString(R.string.displayNameErrorDescriptionShorter)

                    else -> null
                }

                _uiState.update { it.copy(usernameDialog =
                    it.usernameDialog?.copy(
                            inputName = command.name,
                            setEnabled = trimmedName.isNotEmpty() && // can save if we have an input
                                    trimmedName != it.usernameDialog.currentName && // ... and it isn't the same as what is already saved
                                    error == null, // ... and there are no errors
                            error = error
                        )
                    )
                }
            }

            is Commands.OnDonateClicked -> {
                viewModelScope.launch {
                    inAppReviewManager.onEvent(InAppReviewManager.Event.DonateButtonClicked)
                }
                showUrlDialog(URL_DONATE)
            }

            is Commands.HideSimpleDialog -> {
                _uiState.update { it.copy(showSimpleDialog = null) }
            }
        }
    }

    private fun refreshSubscriptionData(){
        //todo PRO implement properly
    }

    sealed class AvatarDialogState() {
        object NoAvatar : AvatarDialogState()
        data class UserAvatar(val data: AvatarUIData) : AvatarDialogState()
        data class TempAvatar(
            val data: ByteArray,
            val isAnimated: Boolean,
            val hasAvatar: Boolean // true if the user has an avatar set already but is in this temp state because they are trying out a new avatar
        ) : AvatarDialogState()
    }

    sealed interface ClearDataState {
        data object Hidden: ClearDataState
        data object Default: ClearDataState
        data object Clearing: ClearDataState
        data object Error: ClearDataState

        sealed interface ConfirmedClearDataState: ClearDataState {
            data object ConfirmNetwork : ConfirmedClearDataState
            data object ConfirmNetworkPro : ConfirmedClearDataState
            data object ConfirmDevicePro : ConfirmedClearDataState
        }
    }

    data class UsernameDialogData(
        val currentName: String?, // the currently saved name
        val inputName: String?, // the name being inputted
        val setEnabled: Boolean,
        val error: String?
    )

    data class UIState(
        val username: String,
        val accountID: String,
        val hasPath: Boolean,
        val version: CharSequence = "",
        val showLoader: Boolean = false,
        val avatarDialogState: AvatarDialogState = AvatarDialogState.NoAvatar,
        val avatarData: AvatarUIData? = null,
        val recoveryHidden: Boolean,
        val showUrlDialog: String? = null,
        val clearDataDialog: ClearDataState = ClearDataState.Hidden,
        val showAvatarDialog: Boolean = false,
        val showAvatarPickerOptionCamera: Boolean = false,
        val showAvatarPickerOptions: Boolean = false,
        val showAnimatedProCTA: Boolean = false,
        val usernameDialog: UsernameDialogData? = null,
        val showSimpleDialog: SimpleDialogData? = null,
        val isPostPro: Boolean,
        val proDataState: ProDataState,
    )

    sealed interface Commands {
        data object ShowClearDataDialog: Commands
        data object HideClearDataDialog: Commands
        data class ShowUrlDialog(val url: String): Commands
        data object HideUrlDialog: Commands
        data object ShowAvatarDialog: Commands
        data class ShowAvatarPickerOptions(val showCamera: Boolean): Commands
        data object HideAvatarPickerOptions: Commands
        data object SaveAvatar: Commands
        data object RemoveAvatar: Commands
        data object OnAvatarDialogDismissed: Commands

        data object ShowUsernameDialog : Commands
        data object HideUsernameDialog : Commands
        data object SetUsername: Commands
        data class UpdateUsername(val name: String): Commands

        data object ShowAnimatedProCTA: Commands
        data object HideAnimatedProCTA: Commands

        data object HideSimpleDialog: Commands

        data object OnDonateClicked: Commands

        data class ClearData(val clearNetwork: Boolean): Commands
    }
}