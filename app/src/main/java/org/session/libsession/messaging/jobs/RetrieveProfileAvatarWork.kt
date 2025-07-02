package org.session.libsession.messaging.jobs

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import org.session.libsession.avatars.AvatarHelper
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.AESGCM
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.DownloadUtilities.downloadFromFileServer
import org.session.libsession.utilities.TextSecurePreferences.Companion.setProfileAvatarId
import org.session.libsession.utilities.TextSecurePreferences.Companion.setProfilePictureURL
import org.session.libsession.utilities.Util
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.HTTP
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Util.SECURE_RANDOM
import java.io.FileOutputStream

@HiltWorker
class RetrieveProfileAvatarWork @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val storage: StorageProtocol,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val recipientAddress = requireNotNull(
            inputData.getString(DATA_ADDRESS)?.let(Address::fromSerialized)
        ) { "Recipient address is required" }

        val profileAvatarUrl = requireNotNull(inputData.getString(DATA_URL)) {
            "Profile avatar URL is required"
        }

        val profileAvatarKey = requireNotNull(inputData.getByteArray(DATA_KEY)) {
            "Profile avatar key is required"
        }

        require(profileAvatarKey.size == 16 || profileAvatarKey.size == 32) {
            "Profile avatar key must be either 16 or 32 bytes long"
        }
        
        val recipient by lazy { Recipient.from(appContext, recipientAddress, false) }

        // Commit '78d1e9d' (fix: open group threads and avatar downloads) had this commented out so
        // it's now limited to just the current user case
        if (
            recipient.isLocalNumber &&
            AvatarHelper.avatarFileExists(appContext, recipientAddress) &&
            Util.equals(profileAvatarUrl, recipient.resolve().profileAvatar)
        ) {
            Log.w(TAG, "Already retrieved profile avatar: $profileAvatarUrl")
            return Result.success()
        }

        try {
            val downloaded = downloadFromFileServer(profileAvatarUrl)
            val decrypted = AESGCM.decrypt(
                downloaded.data,
                offset = downloaded.offset,
                len = downloaded.len,
                symmetricKey = profileAvatarKey
            )

            FileOutputStream(AvatarHelper.getAvatarFile(appContext, recipient.address)).use { out ->
                out.write(decrypted)
            }

            if (recipient.isLocalNumber) {
                setProfileAvatarId(appContext, SECURE_RANDOM.nextInt())
                setProfilePictureURL(appContext, profileAvatarUrl)
            }

            storage.setProfilePicture(recipient, profileAvatarUrl, profileAvatarKey)
            return Result.success()
        }
        catch (e: NonRetryableException) {
            Log.e("Loki", "Failed to download profile avatar from non-retryable error", e)
            return Result.failure()
        }
        catch (e: CancellationException) {
            throw e
        }
        catch (e: Exception) {
            if(e is HTTP.HTTPRequestFailedException && e.statusCode == 404){
                Log.e(TAG, "Failed to download profile avatar from non-retryable error", e)
                return Result.failure()
            } else {
                Log.e(TAG, "Failed to download profile avatar", e)
                return Result.retry()
            }
        }
    }

    companion object {
        private const val DATA_ADDRESS = "address"
        private const val DATA_URL = "url"
        private const val DATA_KEY = "key"

        private const val TAG = "RetrieveProfileAvatarWork"

        fun schedule(
            context: Context,
            recipientAddress: Address,
            profileAvatarUrl: String,
            profileAvatarKey: ByteArray,
        ) {
            val uniqueWorkName = "retrieve-avatar-$recipientAddress"

            val data = Data.Builder()
                .putString(DATA_ADDRESS, recipientAddress.toString())
                .putString(DATA_URL, profileAvatarUrl)
                .putByteArray(DATA_KEY, profileAvatarKey)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<RetrieveProfileAvatarWork>()
                .setInputData(data)
                .build()

            WorkManager.getInstance(context)
                .beginUniqueWork(uniqueWorkName, ExistingWorkPolicy.REPLACE, workRequest)
                .enqueue()
        }
    }
}