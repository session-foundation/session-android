package org.thoughtcrime.securesms.preferences

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canhub.cropper.CropImage
import com.canhub.cropper.CropImageView
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.avatars.AvatarHelper
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ProfileKeyUtil
import org.session.libsession.utilities.ProfilePictureUtilities
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.currentUserName
import org.session.libsignal.utilities.ExternalStorageUtil.getImageDir
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.NoExternalStorageException
import org.session.libsignal.utilities.Util.SECURE_RANDOM
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.preferences.SettingsViewModel.AvatarDialogState.TempAvatar
import org.thoughtcrime.securesms.profiles.ProfileMediaConstraints
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.BitmapDecodingException
import org.thoughtcrime.securesms.util.BitmapUtil
import org.thoughtcrime.securesms.util.NetworkConnectivity
import java.io.File
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: TextSecurePreferences,
    private val configFactory: ConfigFactory,
    private val connectivity: NetworkConnectivity,
    private val avatarUtils: AvatarUtils,
    private val recipientRepository: RecipientRepository,
) : ViewModel() {
    private val TAG = "SettingsViewModel"

    private var tempFile: File? = null

    val hexEncodedPublicKey: String = prefs.getLocalNumber() ?: ""

    private val userRecipient by lazy {
        recipientRepository.getRecipientSync(Address.fromSerialized(hexEncodedPublicKey))
    }

    private val _avatarDialogState: MutableStateFlow<AvatarDialogState> = MutableStateFlow(
        AvatarDialogState.NoAvatar
    )
    val avatarDialogState: StateFlow<AvatarDialogState>
        get() = _avatarDialogState

    private val _showLoader: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val showLoader: StateFlow<Boolean>
        get() = _showLoader

    private val _recoveryHidden: MutableStateFlow<Boolean> = MutableStateFlow(prefs.getHidePassword())
    val recoveryHidden: StateFlow<Boolean>
        get() = _recoveryHidden

    private val _avatarData: MutableStateFlow<AvatarUIData?> = MutableStateFlow(null)
    val avatarData: StateFlow<AvatarUIData?>
        get() = _avatarData

    init {
        updateAvatar()

        // set default dialog ui
        viewModelScope.launch {
            _avatarDialogState.value = getDefaultAvatarDialogState()
        }
    }

    private fun updateAvatar(){
        viewModelScope.launch(Dispatchers.Default) {
            _avatarData.update {
                avatarUtils.getUIDataFromRecipient(userRecipient)
            }
        }
    }

    fun getDisplayName(): String = configFactory.currentUserName

    fun hasAvatar() = configFactory.withUserConfigs { it.userProfile.getPic().url.isNotBlank() }

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
                Log.i(TAG, result.getUriFilePath(context).toString())

                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val profilePictureToBeUploaded =
                            BitmapUtil.createScaledBytes(
                                context,
                                result.getUriFilePath(context).toString(),
                                ProfileMediaConstraints()
                            ).bitmap

                        // update dialog with temporary avatar (has not been saved/uploaded yet)
                        _avatarDialogState.value =
                            AvatarDialogState.TempAvatar(profilePictureToBeUploaded, hasAvatar())
                    } catch (e: BitmapDecodingException) {
                        Log.e(TAG, e)
                    }
                }
            }

            result is CropImage.CancelledResult -> {
                Log.i(TAG, "Cropping image was cancelled by the user")
            }

            else -> {
                Log.e(TAG, "Cropping image failed")
            }
        }
    }

    fun onAvatarDialogDismissed() {
        viewModelScope.launch {
            _avatarDialogState.value = getDefaultAvatarDialogState()
        }
    }

    private suspend fun getDefaultAvatarDialogState() = if (hasAvatar()) AvatarDialogState.UserAvatar(
        avatarUtils.getUIDataFromRecipient(userRecipient)
    )
    else AvatarDialogState.NoAvatar

    fun saveAvatar() {
        val tempAvatar = (avatarDialogState.value as? TempAvatar)?.data
            ?: return Toast.makeText(context, R.string.profileErrorUpdate, Toast.LENGTH_LONG).show()

        if (!hasNetworkConnection()) {
            Log.w(TAG, "Cannot update profile picture - no network connection.")
            Toast.makeText(context, R.string.profileErrorUpdate, Toast.LENGTH_LONG).show()
            return
        }

        val onFail: () -> Unit = {
            Log.e(TAG, "Sync failed when uploading profile picture.")
            Toast.makeText(context, R.string.profileErrorUpdate, Toast.LENGTH_LONG).show()
        }

        syncProfilePicture(tempAvatar, onFail)
    }


    fun removeAvatar() {
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
        viewModelScope.launch(Dispatchers.IO) {
            _showLoader.value = true

            try {
                // Grab the profile key and kick of the promise to update the profile picture
                val encodedProfileKey = ProfileKeyUtil.generateEncodedProfileKey(context)
                val url = ProfilePictureUtilities.upload(profilePicture, encodedProfileKey, context)

                // If the online portion of the update succeeded then update the local state
                AvatarHelper.setAvatar(
                    context,
                    Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)!!),
                    profilePicture
                )

                // When removing the profile picture the supplied ByteArray is empty so we'll clear the local data
                if (profilePicture.isEmpty()) {
                    configFactory.withMutableUserConfigs {
                        it.userProfile.setPic(UserPic.DEFAULT)
                    }

                    // update dialog state
                    _avatarDialogState.value = AvatarDialogState.NoAvatar
                } else {
                    ProfileKeyUtil.setEncodedProfileKey(context, encodedProfileKey)

                    // Attempt to grab the details we require to update the profile picture
                    val profileKey = ProfileKeyUtil.getProfileKey(context)

                    // If we have a URL and a profile key then set the user's profile picture
                    if (url.isNotEmpty() && profileKey.isNotEmpty()) {
                        configFactory.withMutableUserConfigs {
                            it.userProfile.setPic(UserPic(url, profileKey))
                        }
                    }

                    // update dialog state
                    _avatarDialogState.value = AvatarDialogState.UserAvatar(avatarUtils.getUIDataFromRecipient(userRecipient))
                }

            } catch (e: Exception){ // If the sync failed then inform the user
                Log.d(TAG, "Error syncing avatar: $e")
                withContext(Dispatchers.Main) {
                    onFail()
                }
            }

            // Finally update the main avatar
            updateAvatar()
            // And remove the loader animation after we've waited for the attempt to succeed or fail
            _showLoader.value = false
        }
    }

    fun updateName(displayName: String) {
        configFactory.withMutableUserConfigs { it.userProfile.setName(displayName) }
    }

    fun permanentlyHidePassword() {
        //todo we can simplify this once we expose all our sharedPrefs as flows
        prefs.setHidePassword(true)
        _recoveryHidden.update { true }
    }

    fun hasNetworkConnection(): Boolean = connectivity.networkAvailable.value

    sealed class AvatarDialogState() {
        object NoAvatar : AvatarDialogState()
        data class UserAvatar(val data: AvatarUIData) : AvatarDialogState()
        data class TempAvatar(
            val data: ByteArray,
            val hasAvatar: Boolean // true if the user has an avatar set already but is in this temp state because they are trying out a new avatar
        ) : AvatarDialogState()
    }
}