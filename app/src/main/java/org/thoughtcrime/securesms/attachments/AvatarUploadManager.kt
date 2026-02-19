package org.thoughtcrime.securesms.attachments

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.encrypt.Attachments
import network.loki.messenger.libsession_util.util.Bytes
import org.session.libsession.messaging.file_server.FileServerApis
import org.session.libsession.messaging.file_server.FileUploadApi
import org.session.libsession.utilities.AESGCM
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.Util
import org.thoughtcrime.securesms.preferences.MessagingPreferences
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsession.utilities.recipients.RemoteFile.Companion.toRemoteFile
import org.session.libsession.utilities.withMutableUserConfigs
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.api.server.ServerApiRequest
import org.thoughtcrime.securesms.api.server.execute
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.debugmenu.DebugLogGroup
import org.thoughtcrime.securesms.util.AnimatedImageUtils
import org.thoughtcrime.securesms.util.castAwayType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


/**
 * This class handles user avatar uploads and re-uploads.
 */
@Singleton
class AvatarUploadManager @Inject constructor(
    private val application: Application,
    private val configFactory: ConfigFactoryProtocol,
    private val textSecurePreferences: TextSecurePreferences,
    private val preferenceStorage: PreferenceStorage,
    private val localEncryptedFileOutputStreamFactory: LocalEncryptedFileOutputStream.Factory,
    private val attachmentProcessor: AttachmentProcessor,
    private val serverApiExecutor: ServerApiExecutor,
    private val fileUploadApiFactory: FileUploadApi.Factory,
) : AuthAwareComponent {
    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState) {
        preferenceStorage.changes().filter { it.name == MessagingPreferences.DEBUG_AVATAR_REUPLOAD.name }
            .castAwayType()
            .onStart { emit(Unit) }
            .collectLatest {
                AvatarReuploadWorker.schedule(application, preferenceStorage)
            }
    }

    override fun onLoggedOut() {
        AvatarReuploadWorker.cancel(application)
    }

    /**
     * Uploads the given avatar image data to the file server, updates the user profile to point to
     * the new avatar, and deletes any old avatar from local storage.
     *
     * @param pictureData The raw image data of the avatar to upload. Should be unencrypted real image data.
     * @param isReupload Whether this is a re-upload of an existing avatar.
     */
    suspend fun uploadAvatar(
        pictureData: ByteArray,
        isReupload: Boolean, // Whether this is a re-upload of an existing avatar
    ) = withContext(Dispatchers.IO) {
        check(pictureData.isNotEmpty()) {
            "Should not upload an empty avatar"
        }

        val usesDeterministicEncryption = preferenceStorage[MessagingPreferences.FORCES_DETERMINISTIC_ATTACHMENT_ENCRYPTION]
        val result = if (usesDeterministicEncryption) {
            attachmentProcessor.encryptDeterministically(
                plaintext = pictureData,
                domain = Attachments.Domain.ProfilePic
            )
        } else {
            val key = Util.getSecretBytes(PROFILE_KEY_LENGTH)
            val ciphertext = AESGCM.encrypt(pictureData, key)
            AttachmentProcessor.EncryptResult(ciphertext = ciphertext, key = key)
        }

        val fileServer = preferenceStorage[MessagingPreferences.ALTERNATIVE_FILE_SERVER] ?: FileServerApis.DEFAULT_FILE_SERVER
        val uploadResult = serverApiExecutor.execute(
            ServerApiRequest(
                fileServer = fileServer,
                api = fileUploadApiFactory.create(
                    fileServer = fileServer,
                    data = result.ciphertext,
                    usedDeterministicEncryption = usesDeterministicEncryption,
                    customExpiresSeconds = DEBUG_AVATAR_TTL.takeIf { preferenceStorage[MessagingPreferences.FORCED_SHORT_TTL] }?.inWholeSeconds
                )
            )
        )

        Log.d(DebugLogGroup.AVATAR.label, "Avatar upload finished with $uploadResult")

        val remoteFile = RemoteFile.Encrypted(url = uploadResult.fileUrl, key = Bytes(result.key))

        // To save us from downloading this avatar again, we store the data as it would be downloaded
        localEncryptedFileOutputStreamFactory.create(
            file = AvatarDownloadManager.computeFileName(application, remoteFile),
            meta = FileMetadata(expiryTime = uploadResult.expires?.toInstant())
        ).use {
            it.write(pictureData)
        }

        val isAnimated = AnimatedImageUtils.isAnimated(pictureData)

        Log.d(DebugLogGroup.AVATAR.label, "Avatar file written to local storage")

        // Now that we have the file both locally and remotely, we can update the user profile
        val oldPic = configFactory.withMutableUserConfigs { configs ->
            val result = configs.userProfile.getPic()
            val userPic = remoteFile.toUserPic()
            if (isReupload) {
                configs.userProfile.setReuploadedPic(userPic)
            } else {
                configs.userProfile.setPic(userPic)
            }

            configs.userProfile.setAnimatedAvatar(isAnimated)
            result.toRemoteFile()
        }

        if (oldPic != null) {
            // If we had an old avatar, delete it from local storage
            val oldFile = AvatarDownloadManager.computeFileName(application, oldPic)
            if (oldFile.exists()) {
                Log.d(DebugLogGroup.AVATAR.label, "Deleting old avatar file: $oldFile")
                oldFile.delete()
            }
        }
    }

    companion object {
        private const val TAG = "AvatarUploadManager"

        private const val PROFILE_KEY_LENGTH = 32

        private val DEBUG_AVATAR_TTL: Duration = 30.seconds
    }
}