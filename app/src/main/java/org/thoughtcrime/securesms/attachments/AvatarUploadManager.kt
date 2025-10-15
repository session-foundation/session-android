package org.thoughtcrime.securesms.attachments

import android.app.Application
import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.encrypt.Attachments
import network.loki.messenger.libsession_util.util.Bytes
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.Util
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsession.utilities.recipients.RemoteFile.Companion.toRemoteFile
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsignal.streams.ProfileCipherOutputStream
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import org.thoughtcrime.securesms.util.DateUtils.Companion.millsToInstant
import org.thoughtcrime.securesms.util.DateUtils.Companion.secondsToInstant
import org.thoughtcrime.securesms.util.castAwayType
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
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
    localEncryptedFileInputStreamFactory: LocalEncryptedFileInputStream.Factory,
    private val localEncryptedFileOutputStreamFactory: LocalEncryptedFileOutputStream.Factory,
    private val fileServerApi: FileServerApi,
    private val attachmentProcessor: AttachmentProcessor,
) : OnAppStartupComponent {
    init {
        // Manage scheduling/cancellation of the AvatarReuploadWorker based on login state
        scope.launch {
            prefs.watchLocalNumber()
                .map { it != null }
                .distinctUntilChanged()
                .collectLatest { loggedIn ->
                    if (loggedIn) {
                        AvatarReuploadWorker.schedule(application)
                    } else {
                        AvatarReuploadWorker.cancel(application)
                    }
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val reuploadState: StateFlow<Unit> = prefs.watchLocalNumber()
        .map { it != null }
        .flatMapLatest { isLoggedIn ->
            if (isLoggedIn) {
                configFactory.userConfigsChanged(onlyConfigTypes = setOf(UserConfigType.USER_PROFILE))
                    .castAwayType()
                    .onStart { emit(Unit) }
                    .map {
                        val (pic, lastUpdated) = configFactory.withUserConfigs { configs ->
                            configs.userProfile.getPic() to configs.userProfile.getProfileUpdatedSeconds().secondsToInstant()
                        }

                        pic.toRemoteFile()?.let { it to lastUpdated }
                    }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .mapLatest { (remoteFile, lastUpdated) ->
                        val localFile = AvatarDownloadWorker.computeFileName(application, remoteFile)

                        waitUntilExists(localFile)
                        Log.d(TAG, "About to look at file $localFile for re-upload")

                        val expiringIn = runCatching {
                            localEncryptedFileInputStreamFactory.create(localFile)
                                .use { it.meta.expiryTime }
                        }.onFailure {
                            Log.w(TAG, "Failed to read expiry time from $localFile", it)
                        }.getOrNull() ?: (localFile.lastModified() + DEFAULT_AVATAR_TTL.inWholeMilliseconds).millsToInstant()!!

                        Log.d(TAG, "Avatar expiring at $expiringIn")
                        val now = Instant.now()
                        if (expiringIn.isAfter(now)) {
                            delay(expiringIn.toEpochMilli() - now.toEpochMilli())
                        }

                        Log.d(TAG, "Avatar expired, re-uploading")
                        uploadAvatar(
                            pictureData = localEncryptedFileInputStreamFactory.create(localFile)
                                .use { it.readBytes() },
                            isReupload = true,
                        )
                    }
            } else {
                emptyFlow()
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, Unit)



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
            val ciphertext = ByteArrayOutputStream().use { outputStream ->
                ProfileCipherOutputStream(outputStream, key).use {
                    it.write(pictureData)
                }

                outputStream.toByteArray()
            }

            AttachmentProcessor.EncryptResult(ciphertext = ciphertext, key = key)
        }

        val uploadResult = fileServerApi.upload(
            file = result.ciphertext,
            usedDeterministicEncryption = usesDeterministicEncryption,
            customExpiresDuration = DEBUG_AVATAR_TTL.takeIf { prefs.forcedShortTTL() }
        ).await()

        Log.d(TAG, "Avatar upload finished with $uploadResult")

        val remoteFile = RemoteFile.Encrypted(url = uploadResult.fileUrl, key = Bytes(result.key))

        // To save us from downloading this avatar again, we store the data as it would be downloaded
        localEncryptedFileOutputStreamFactory.create(
            file = AvatarDownloadWorker.computeFileName(application, remoteFile),
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
            val oldFile = AvatarDownloadWorker.computeFileName(application, oldPic)
            if (oldFile.exists()) {
                Log.d(TAG, "Deleting old avatar file: $oldFile")
                oldFile.delete()
            }
        }
    }

    companion object {
        private const val TAG = "AvatarUploadManager"

        private const val PROFILE_KEY_LENGTH = 32

        private val DEFAULT_AVATAR_TTL: Duration = 14.days
        private val DEBUG_AVATAR_TTL: Duration = 30.seconds
    }
}