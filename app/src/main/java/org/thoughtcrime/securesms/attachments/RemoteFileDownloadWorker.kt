package org.thoughtcrime.securesms.attachments

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.hasKeyWithValueOfType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import network.loki.messenger.libsession_util.util.Bytes
import org.session.libsession.avatars.AvatarHelper
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.AESGCM
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import org.session.libsignal.utilities.ByteArraySlice.Companion.write
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.database.RecipientSettingsDatabase
import org.thoughtcrime.securesms.util.DateUtils.Companion.millsToInstant
import org.thoughtcrime.securesms.util.getRootCause
import java.io.File
import java.security.MessageDigest
import java.time.Duration

@HiltWorker
class RemoteFileDownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val localEncryptedFileOutputStreamFactory: LocalEncryptedFileOutputStream.Factory,
    private val localEncryptedFileInputStreamFactory: LocalEncryptedFileInputStream.Factory,
    private val prefs: TextSecurePreferences,
    private val recipientSettingsDatabase: RecipientSettingsDatabase,
    private val configFactory: ConfigFactoryProtocol,
) : CoroutineWorker(context, params) {
    private val file: RemoteFile by lazy {
        when {
            inputData.hasKeyWithValueOfType<String>(ARG_ENCRYPTED_URL) -> RemoteFile.Encrypted(
                url = inputData.getString(ARG_ENCRYPTED_URL)!!,
                key = Bytes(requireNotNull(inputData.getByteArray(ARG_ENCRYPTED_KEY)))
            )

            else -> RemoteFile.Community(
                communityServerBaseUrl = requireNotNull(inputData.getString(ARG_COMMUNITY_URL)) {
                    "RemoteFileDownloadWorker requires a community URL"
                },
                roomId = requireNotNull(inputData.getString(ARG_COMMUNITY_ROOM_ID)) {
                    "RemoteFileDownloadWorker requires a community room ID"
                },
                fileId = requireNotNull(inputData.getString(ARG_COMMUNITY_FILE_ID)) {
                    "RemoteFileDownloadWorker requires a community file ID"
                }
            )
        }
    }

    override suspend fun doWork(): Result {
        val downloaded = computeFileName(context, file)

        // If the downloaded file exists, it can either mean it's already downloaded or the previous
        // download failed permanently, so we can skip it.
        if (downloaded.exists()) {
            try {
                localEncryptedFileInputStreamFactory.create(downloaded).use {
                    if (it.meta.hasPermanentDownloadError) {
                        Log.w(TAG, "File $downloaded is marked as a permanent error, skipping download")
                        return Result.failure()
                    } else {
                        Log.i(TAG, "File $downloaded already exists, skipping download")
                        return Result.success()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read downloaded file $downloaded", e)
                // If we can't read the file, we assume it's corrupted and we need to delete it.
                if (!downloaded.delete()) {
                    Log.w(TAG, "Failed to delete corrupted file $downloaded")
                }
            }
        }

        Log.d(TAG, "Start downloading file from $file")

        val result = runCatching { downloadAndDecryptFile() }

        val (bytes, meta) = if (result.isSuccess) {
            result.getOrThrow()
        } else if (result.exceptionOrNull() is NonRetryableException ||
            result.exceptionOrNull()?.getRootCause<OnionRequestAPI.HTTPRequestFailedAtDestinationException>()?.statusCode == 404) {
            Log.w(TAG, "Download failed permanently for file $file", result.exceptionOrNull())
            // Write an empty file with a permanent error metadata if the download failed permanently.
            byteArrayOf().view() to FileMetadata(
                hasPermanentDownloadError = true
            )
        } else {
            Log.w(TAG, "Download failed for file $file", result.exceptionOrNull())
            // If the download failed otherwise, we retry the work.
            return Result.retry()
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
            return Result.success()
        } catch (e: CancellationException) {
            Log.i(TAG, "Download cancelled for file $file")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download file $file", e)
            return Result.failure()
        } finally {
            tmpFileToClean?.delete()
        }
    }

    private fun findRecipientsForProfilePic(profilePicUrl: String): Set<Address> {
        return buildSet {
            addAll(recipientSettingsDatabase.findRecipientsForProfilePic(profilePicUrl))

            val allGroups = configFactory.withUserConfigs { configs ->
                if (configs.userProfile.getPic().url == profilePicUrl) {
                    // If the profile picture URL matches the one in the user config, add the local number
                    // as a recipient as well.
                    add(Address.fromSerialized(prefs.getLocalNumber()!!))
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

    private suspend fun downloadAndDecryptFile(): Pair<ByteArraySlice, FileMetadata> {
        return when (val file = file) {
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
                            expiryTime = if (address.address == prefs.getLocalNumber()) {
                                TextSecurePreferences.getProfileExpiry(context).millsToInstant()
                            } else {
                                null
                            }
                        )

                        Log.d(TAG, "Migrated old avatar file for ${address.debugString} to new location")

                        avatarFile.delete()
                        return data.view() to meta
                    }
                }

                val fileId = requireNotNull(FileServerApi.getFileIdFromUrl(file.url)) {
                    "RemoteFileDownloadWorker currently only supports downloading files from Session's file server"
                }

                val response = FileServerApi.download(fileId).await()
                Log.d(TAG, "Downloaded file from file server: $file")

                // Decrypt data
                val decrypted = AESGCM.decrypt(
                    ivAndCiphertext = response.body.data,
                    offset = response.body.offset,
                    len = response.body.len,
                    symmetricKey = file.key.data
                )

                decrypted.view() to FileMetadata(expiryTime = response.expires?.toInstant())

            }

            is RemoteFile.Community -> {
                val data = OpenGroupApi.download(
                    fileId = file.fileId,
                    room = file.roomId,
                    server = file.communityServerBaseUrl
                ).await()

                data to FileMetadata()
            }
        }
    }

    companion object {
        const val TAG = "RemoteFileDownloadWorker"

        private const val ARG_ENCRYPTED_URL = "encrypted_url"
        private const val ARG_ENCRYPTED_KEY = "encrypted_key"

        private const val ARG_COMMUNITY_URL = "community_url"
        private const val ARG_COMMUNITY_ROOM_ID = "community_room_id"
        private const val ARG_COMMUNITY_FILE_ID = "community_file_id"

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

        // Deterministically get the file path for the given remote file.
        fun computeFileName(context: Context, remote: RemoteFile): File {
            return File(context.cacheDir, "remote_files/${remote.sha256Hash()}")
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }

        private fun uniqueWorkName(remote: RemoteFile): String {
            return "download-remote-file-${remote.sha256Hash()}"
        }

        fun cancel(context: Context, file: RemoteFile): Operation {
            return WorkManager.getInstance(context).cancelUniqueWork(
                uniqueWorkName(file)
            )
        }

        /**
         * @param isOldAvatarOf used to indicate that this file is an avatar of a specific address. This
         * information is optional and only used for migration purposes (to move the avatar from
         * the old location to the new one). Once the migration grace period is over, this parameter
         * shall be removed.
         */
        fun enqueue(context: Context, file: RemoteFile): Flow<WorkInfo?> {
            val input = when (file) {
                is RemoteFile.Encrypted -> Data.Builder()
                    .putString(ARG_ENCRYPTED_URL, file.url)
                    .putByteArray(ARG_ENCRYPTED_KEY, file.key.data)

                is RemoteFile.Community -> Data.Builder()
                    .putString(ARG_COMMUNITY_URL, file.communityServerBaseUrl)
                    .putString(ARG_COMMUNITY_ROOM_ID, file.roomId)
                    .putString(ARG_COMMUNITY_FILE_ID, file.fileId)
            }

            val request = OneTimeWorkRequestBuilder<RemoteFileDownloadWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(5))
                .addTag(TAG)
                .setInputData(input.build())
                .build()

            val workName = uniqueWorkName(file)
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    workName,
                    ExistingWorkPolicy.KEEP,
                    request
                )

            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(workName)
                .mapNotNull { it.firstOrNull() }
        }
    }
}