package org.thoughtcrime.securesms.attachments

import android.content.Context
import androidx.annotation.CheckResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.session.libsession.avatars.AvatarHelper
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.network.model.OnionError
import org.session.libsession.utilities.AESGCM
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsession.utilities.withGroupConfigs
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import org.session.libsignal.utilities.ByteArraySlice.Companion.write
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.database.RecipientSettingsDatabase
import org.thoughtcrime.securesms.util.DateUtils.Companion.millsToInstant
import org.thoughtcrime.securesms.util.getRootCause
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A manager that handles downloading and caching user avatars.
 */
@Singleton
class AvatarDownloadManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val localEncryptedFileOutputStreamFactory: LocalEncryptedFileOutputStream.Factory,
    private val localEncryptedFileInputStreamFactory: LocalEncryptedFileInputStream.Factory,
    private val prefs: TextSecurePreferences,
    private val recipientSettingsDatabase: RecipientSettingsDatabase,
    private val configFactory: ConfigFactoryProtocol,
    private val fileServerApi: FileServerApi,
    private val attachmentProcessor: AttachmentProcessor,
    private val loginStateRepository: LoginStateRepository,
) {
    /**
     * A map of mutexes to synchronize downloads for each remote file.
     */
    private val downloadMutex = ConcurrentHashMap<RemoteFile, Mutex>()

    // Return null if the file doesn't exist locally or corrupted
    private fun openDownloadedFile(file: File): InputStream? {
        if (!file.exists()) return null

        val downloadedFile = runCatching {
            localEncryptedFileInputStreamFactory.create(file)
        }.onFailure { e ->
            Log.w(TAG, "Failed to read downloaded file $file", e)
            // If we can't read the file, we assume it's corrupted and we need to delete it.
            if (!file.delete()) {
                Log.w(TAG, "Failed to delete corrupted file $file")
            }
        }.getOrNull()

        when {
            downloadedFile?.meta?.hasPermanentDownloadError == true -> {
                throw NonRetryableException("Previous download failed permanently for file $file")
            }

            downloadedFile != null -> {
                Log.d(TAG, "Avatar file already downloaded: $file")
                return downloadedFile
            }

            else -> return null
        }
    }


    /**
     * Downloads the given remote file, returning an InputStream to read the downloaded file.
     * If the file has already been downloaded, returns an InputStream to read the cached file.
     *
     * @throws NonRetryableException if the download failed permanently.
     * @return InputStream to read the downloaded file. It's the caller's responsibility to close the stream.
     */
    @CheckResult
    suspend fun download(file: RemoteFile): InputStream {
        val downloaded = computeFileName(context, file)

        // Quickly look at the downloaded file without holding the lock,
        // in case we already have it downloaded.
        openDownloadedFile(downloaded)?.let { return it }

        // Now given we don't have a locally download file, we will hold the mutex for this
        // file when we try to download it from network
        downloadMutex.getOrPut(file) { Mutex() }.withLock {
            // Once we hold the lock, we MUST check the local file again just in case
            // another coroutine already downloaded it while we were waiting for the lock
            openDownloadedFile(downloaded)?.let { return it }

            Log.d(TAG, "Start downloading file from $file")

            val (bytes, meta) = try {
                downloadAndDecryptFile(file)
            } catch (e: Exception) {
                if (e.getRootCause<NonRetryableException>() != null ||
                    e.getRootCause<OnionError.DestinationError>()?.status?.code == 404 //todo ONION does this check still work in the current setup
                ) {
                    Log.w(TAG, "Download failed permanently for file $file", e)
                    // Write an empty file with a permanent error metadata if the download failed permanently.
                    localEncryptedFileOutputStreamFactory.create(
                        downloaded, FileMetadata(
                            hasPermanentDownloadError = true
                        )
                    ).use {}

                    throw NonRetryableException("Download failed permanently for file $file", e)
                } else {
                    throw e
                }
            }


            // A temp file to clear, if it exists
            var tmpFileToClean: File? = null
            try {
                // Re-encrypt the file with our streaming cipher, and encode it with the metadata,
                // and doing it to a temporary file first.
                downloaded.parentFile!!.mkdirs()
                val tmpFile = File.createTempFile("downloaded-", null, downloaded.parentFile)
                    .also { tmpFileToClean = it }

                localEncryptedFileOutputStreamFactory.create(tmpFile, meta)
                    .use { fos -> fos.write(bytes) }

                // Once done, rename the temporary file to the final file name.
                check(tmpFile.renameTo(downloaded)) {
                    "Failed to rename temporary file ${tmpFile.absolutePath} to $downloaded"
                }

                // Since we successfully moved the file, we don't need to delete the temporary file anymore.
                tmpFileToClean = null
                Log.d(TAG, "Successfully downloaded file $file")
                return localEncryptedFileInputStreamFactory.create(downloaded)
            } catch (e: CancellationException) {
                Log.i(TAG, "Download cancelled for file $file")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download file $file", e)
                throw e
            } finally {
                tmpFileToClean?.delete()
            }
        }
    }

    private fun findRecipientsForProfilePic(profilePicUrl: String): Set<Address> {
        return buildSet {
            addAll(recipientSettingsDatabase.findRecipientsForProfilePic(profilePicUrl))

            val allGroups = configFactory.withUserConfigs { configs ->
                if (configs.userProfile.getPic().url == profilePicUrl) {
                    // If the profile picture URL matches the one in the user config, add the local number
                    // as a recipient as well.
                    add(Address.fromSerialized(loginStateRepository.requireLocalNumber()))
                }

                // Search through all contacts to find any that have this profile picture URL.
                configs.contacts.all()
                    .asSequence()
                    .filter { it.profilePicture.url == profilePicUrl }
                    .mapTo(this) { it.id.toAddress() }

                // Search through all blinded contacts to find any that have this profile picture URL.
                configs.contacts.allBlinded()
                    .asSequence()
                    .filter { it.profilePic.url == profilePicUrl }
                    .mapTo(this) { it.id.toAddress() }

                // Return the group addresses for further processing.
                configs.userGroups.allClosedGroupInfo()
            }

            for (group in allGroups) {
                configFactory.withGroupConfigs(AccountId(group.groupAccountId)) { configs ->
                    configs.groupMembers.all()
                        .asSequence()
                        .filter { it.profilePic()?.url == profilePicUrl }
                        .mapTo(this) { it.accountId().toAddress() }
                }
            }
        }
    }

    private suspend fun downloadAndDecryptFile(file: RemoteFile): Pair<ByteArraySlice, FileMetadata> {
        return when (file) {
            is RemoteFile.Encrypted -> {
                // Look at the db and find out which addresses have this file as their profile picture,
                // then we will look at the old location of the avatar file and migrate it to the new location.
                // Once the migration grace period is over, this copying code shall be removed.
                for (address in findRecipientsForProfilePic(file.url)) {
                    val avatarFile = AvatarHelper.getAvatarFile(context, address)
                    if (avatarFile.exists()) {
                        val data = runCatching { avatarFile.readBytes() }
                            .onFailure { Log.w(TAG, "Error reading old avatar file", it) }
                            .getOrNull() ?: continue

                        val meta = FileMetadata(
                            expiryTime = if (address.address == loginStateRepository.requireLocalNumber()) {
                                TextSecurePreferences.getProfileExpiry(context).millsToInstant()
                            } else {
                                null
                            }
                        )

                        Log.d(
                            TAG,
                            "Migrated old avatar file for ${address.debugString} to new location"
                        )

                        avatarFile.delete()
                        return data.view() to meta
                    }
                }

                val result = fileServerApi.parseAttachmentUrl(file.url.toHttpUrl())

                val response = fileServerApi.download(
                    fileId = result.fileId,
                    fileServer = result.fileServer,
                )

                Log.d(TAG, "Downloaded file from file server: $file")

                // Decrypt data
                val decrypted = if (result.usesDeterministicEncryption) {
                    attachmentProcessor.decryptDeterministically(
                        ciphertext = response.body,
                        key = file.key.data
                    )
                } else {
                    AESGCM.decrypt(
                        ivAndCiphertext = response.body.data,
                        offset = response.body.offset,
                        len = response.body.len,
                        symmetricKey = file.key.data
                    ).view()
                }

                decrypted to FileMetadata(expiryTime = response.expires?.toInstant())

            }

            is RemoteFile.Community -> {
                val data = OpenGroupApi.download(
                    fileId = file.fileId,
                    room = file.roomId,
                    server = file.communityServerBaseUrl
                )

                data to FileMetadata()
            }
        }
    }

    companion object {
        private const val TAG = "AvatarDownloadManager"
        private const val SUBDIRECTORY = "remote_files"

        private fun RemoteFile.sha256Hash(): String {
            val hash = MessageDigest.getInstance("SHA-256")
            when (this) {
                is RemoteFile.Encrypted -> {
                    hash.update(url.lowercase().trim().toByteArray())
                    hash.update(key.data)
                }

                is RemoteFile.Community -> {
                    hash.update(communityServerBaseUrl.lowercase().trim().toByteArray())
                    hash.update(roomId.lowercase().trim().toByteArray())
                    hash.update(fileId.lowercase().trim().toByteArray())
                }
            }
            return hash.digest().toHexString()
        }

        private fun downloadsDirectory(context: Context): File =
            File(context.cacheDir, SUBDIRECTORY)

        // Deterministically get the file path for the given remote file.
        fun computeFileName(context: Context, remote: RemoteFile): File {
            return File(downloadsDirectory(context), remote.sha256Hash())
        }

        /** Returns all currently downloaded files (may be empty). */
        fun listDownloadedFiles(context: Context): List<File> {
            val directory = downloadsDirectory(context)
            return directory.listFiles()?.toList().orEmpty()
        }

    }
}