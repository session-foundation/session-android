package org.thoughtcrime.securesms.attachments

import android.app.Application
import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.util.Bytes
import okio.Buffer
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsession.utilities.recipients.RemoteFile.Companion.toRemoteFile
import org.session.libsession.utilities.userConfigsChanged
import org.session.libsignal.streams.DigestingRequestBody
import org.session.libsignal.streams.ProfileCipherOutputStream
import org.session.libsignal.streams.ProfileCipherOutputStreamFactory
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.ProfileAvatarData
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import org.thoughtcrime.securesms.util.DateUtils.Companion.millsToInstant
import org.thoughtcrime.securesms.util.castAwayType
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom
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
    private val localEncryptedFileOutputStreamFactory: LocalEncryptedFileOutputStream.Factory
) : OnAppStartupComponent {
    @OptIn(ExperimentalCoroutinesApi::class)
    val reuploadState: StateFlow<Unit> = prefs.watchLocalNumber()
        .map { it != null }
        .flatMapLatest { isLoggedIn ->
            if (isLoggedIn) {
                configFactory.userConfigsChanged(onlyConfigTypes = setOf(UserConfigType.USER_PROFILE))
                    .castAwayType()
                    .onStart { emit(Unit) }
                    .map {
                        configFactory.withUserConfigs { it.userProfile.getPic() }
                            .takeIf { it.url.isNotBlank() }
                            ?.toRemoteFile()
                    }
                    .filterNotNull()
                    .map { RemoteFileDownloadWorker.computeFileName(application, it) }
                    .distinctUntilChanged()
                    .mapLatest { localFile ->
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
                                .use { it.readBytes() }
                        )
                    }
            } else {
                emptyFlow()
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, Unit)

    private suspend fun waitUntilExists(file: File) {
        if (file.exists()) return

        // First make sure its parent directory exists so we can observe it
        file.parentFile!!.mkdirs()

        var fileObserver: FileObserver

        return suspendCancellableCoroutine { cont ->
            // The fileObserver variable MUST exist until the coroutine is resumed or cancelled,
            // otherwise the observer will be garbage collected and the coroutine will never resume.
            @Suppress("DEPRECATION")
            fileObserver = object : FileObserver(
                file.parentFile!!.absolutePath,
                CREATE or MOVED_TO
            ) {
                override fun onEvent(event: Int, path: String?) {
                    Log.d(TAG, "FileObserver event: $event, path: $path")
                    if (path == file.name) {
                        stopWatching()
                        cont.resume(Unit)
                    }
                }
            }

            fileObserver.startWatching()
        }
    }


    suspend fun uploadAvatar(
        pictureData: ByteArray
    ) = withContext(Dispatchers.IO) {
        check(pictureData.isNotEmpty()) {
            "Should not upload an empty avatar"
        }

        val inputStream = ByteArrayInputStream(pictureData)
        val outputStream =
            ProfileCipherOutputStream.getCiphertextLength(pictureData.size.toLong())
        val profileKey = ByteArray(PROFILE_KEY_LENGTH)
            .also(SecureRandom()::nextBytes)

        val pad = ProfileAvatarData(
            inputStream,
            outputStream,
            "image/jpeg",
            ProfileCipherOutputStreamFactory(profileKey)
        )
        val drb = DigestingRequestBody(
            pad.data,
            pad.outputStreamFactory,
            pad.contentType,
            pad.dataLength,
        )
        val b = Buffer()
        drb.writeTo(b)
        val data = b.readByteArray()

        val uploadResult = FileServerApi.upload(
            file = data,
            customExpiresDuration = DEBUG_AVATAR_TTL.takeIf { prefs.forcedShortTTL() }
        ).await()

        Log.d(TAG, "Avatar upload finished with $uploadResult")

        val remoteFile = RemoteFile.Encrypted(url = uploadResult.fileUrl, key = Bytes(profileKey))

        // To save us from downloading this avatar again, we store the data as it would be downloaded
        localEncryptedFileOutputStreamFactory.create(
            file = RemoteFileDownloadWorker.computeFileName(application, remoteFile),
            meta = FileMetadata(expiryTime = uploadResult.expires?.toInstant())
        ).use {
            it.write(pictureData)
        }

        Log.d(TAG, "Avatar file written to local storage")

        // Now that we have the file both locally and remotely, we can update the user profile
        val oldPic = configFactory.withMutableUserConfigs {
            val result = it.userProfile.getPic()
            it.userProfile.setPic(remoteFile.toUserPic())
            result.toRemoteFile()
        }

        if (oldPic != null) {
            // If we had an old avatar, delete it from local storage
            val oldFile = RemoteFileDownloadWorker.computeFileName(application, oldPic)
            if (oldFile.exists()) {
                Log.d(TAG, "Deleting old avatar file: $oldFile")
                oldFile.delete()
            }
        }

        prefs.lastProfileUpdated = Instant.now()
    }

    companion object {
        private const val TAG = "AvatarUploadManager"

        private const val PROFILE_KEY_LENGTH = 32

        private val DEFAULT_AVATAR_TTL: Duration = 14.days
        private val DEBUG_AVATAR_TTL: Duration = 30.seconds
    }
}