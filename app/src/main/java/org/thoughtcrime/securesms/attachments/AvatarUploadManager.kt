package org.thoughtcrime.securesms.attachments

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.encrypt.Attachments
import network.loki.messenger.libsession_util.util.Bytes
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.utilities.AESGCM
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.Util
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsession.utilities.recipients.RemoteFile.Companion.toRemoteFile
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
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
    private val prefs: TextSecurePreferences,
    @ManagerScope scope: CoroutineScope,
    private val localEncryptedFileOutputStreamFactory: LocalEncryptedFileOutputStream.Factory,
    private val fileServerApi: FileServerApi,
    private val attachmentProcessor: AttachmentProcessor,
) : OnAppStartupComponent {
    init {
        // Manage scheduling/cancellation of the AvatarReuploadWorker based on login state
        scope.launch {
            combine(
                prefs.watchLocalNumber()
                    .map { it != null }
                    .distinctUntilChanged(),
                TextSecurePreferences._events.filter { it == TextSecurePreferences.DEBUG_AVATAR_REUPLOAD }
                    .castAwayType()
                    .onStart { emit(Unit) }
            ) { loggedIn, _ -> loggedIn }
                .collectLatest { loggedIn ->
                    if (loggedIn) {
                        AvatarReuploadWorker.schedule(application, prefs)
                    } else {
                        AvatarReuploadWorker.cancel(application)
                    }
                }
        }
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

        val usesDeterministicEncryption = prefs.forcesDeterministicAttachmentEncryption
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

        val uploadResult = fileServerApi.upload(
            file = result.ciphertext,
            fileServer = prefs.alternativeFileServer ?: FileServerApi.DEFAULT_FILE_SERVER,
            usedDeterministicEncryption = usesDeterministicEncryption,
            customExpiresDuration = DEBUG_AVATAR_TTL.takeIf { prefs.forcedShortTTL() }
        )

        Log.d(TAG, "Avatar upload finished with $uploadResult")

        val remoteFile = RemoteFile.Encrypted(url = uploadResult.fileUrl, key = Bytes(result.key))

        // To save us from downloading this avatar again, we store the data as it would be downloaded
        localEncryptedFileOutputStreamFactory.create(
            file = AvatarDownloadManager.computeFileName(application, remoteFile),
            meta = FileMetadata(expiryTime = uploadResult.expires?.toInstant())
        ).use {
            it.write(pictureData)
        }

        Log.d(TAG, "Avatar file written to local storage")

        // Now that we have the file both locally and remotely, we can update the user profile
        val oldPic = configFactory.withMutableUserConfigs {
            val result = it.userProfile.getPic()
            val userPic = remoteFile.toUserPic()
            if (isReupload) {
                it.userProfile.setPic(userPic)

                // TODO: We'll need to call this when the libsession re-enables the re-uploaded
                // avatar logic.
                // it.userProfile.setReuploadedPic(userPic)
            } else {
                it.userProfile.setPic(userPic)
            }

            result.toRemoteFile()
        }

        if (oldPic != null) {
            // If we had an old avatar, delete it from local storage
            val oldFile = AvatarDownloadManager.computeFileName(application, oldPic)
            if (oldFile.exists()) {
                Log.d(TAG, "Deleting old avatar file: $oldFile")
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