package org.session.libsession.messaging.jobs

import org.session.libsession.avatars.AvatarHelper
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.utilities.AESGCM
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.DownloadUtilities.downloadFromFileServer
import org.session.libsession.utilities.TextSecurePreferences.Companion.setProfileAvatarId
import org.session.libsession.utilities.TextSecurePreferences.Companion.setProfilePictureURL
import org.session.libsession.utilities.Util.equals
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.HTTP
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Util.SECURE_RANDOM
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentSkipListSet

class RetrieveProfileAvatarJob(
    private val profileAvatar: String?, val recipientAddress: Address,
    private val profileKey: ByteArray?
): Job {
    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0
    override val maxFailureCount: Int = 3

    companion object {
        val TAG = RetrieveProfileAvatarJob::class.simpleName
        val KEY: String = "RetrieveProfileAvatarJob"

        // Keys used for database storage
        private const val PROFILE_AVATAR_KEY = "profileAvatar"
        private const val RECEIPIENT_ADDRESS_KEY = "recipient"
        private const val PROFILE_KEY = "profileKey"

        val errorUrls = ConcurrentSkipListSet<String>()

    }

    override suspend fun execute(dispatcherName: String) {
        val delegate = delegate ?: return Log.w(TAG, "RetrieveProfileAvatarJob has no delegate method to work with!")
        if (profileAvatar != null && profileAvatar in errorUrls) return delegate.handleJobFailed(this, dispatcherName, Exception("Profile URL 404'd this app instance"))
        val context = MessagingModuleConfiguration.shared.context
        val storage = MessagingModuleConfiguration.shared.storage
        val recipient = MessagingModuleConfiguration.shared.recipientRepository.getRecipient(recipientAddress)
            ?: Recipient.empty(recipientAddress)

        if (profileKey == null || (profileKey.size != 32 && profileKey.size != 16)) {
            return delegate.handleJobFailedPermanently(this, dispatcherName, Exception("Recipient profile key is gone!"))
        }

        // Commit '78d1e9d' (fix: open group threads and avatar downloads) had this commented out so
        // it's now limited to just the current user case
        if (
                recipient.isLocalNumber &&
                AvatarHelper.avatarFileExists(context, recipientAddress) &&
                equals(profileAvatar, recipient.profileAvatar)
        ) {
            Log.w(TAG, "Already retrieved profile avatar: $profileAvatar")
            return
        }

        if (profileAvatar.isNullOrEmpty()) {
            Log.w(TAG, "Removing profile avatar for: $recipientAddress" )

            if (recipient.isLocalNumber) {
                setProfileAvatarId(context, SECURE_RANDOM.nextInt())
                setProfilePictureURL(context, null)
            }

            AvatarHelper.delete(context, recipientAddress)
//            storage.setProfilePicture(recipientAddress, null, null)
            return
        }


        try {
            val downloaded = downloadFromFileServer(profileAvatar)
            val decrypted = AESGCM.decrypt(
                downloaded.data,
                offset = downloaded.offset,
                len = downloaded.len,
                symmetricKey = profileKey
            )

            FileOutputStream(AvatarHelper.getAvatarFile(context, recipientAddress)).use { out ->
                out.write(decrypted)
            }

            if (recipient.isLocalNumber) {
                setProfileAvatarId(context, SECURE_RANDOM.nextInt())
                setProfilePictureURL(context, profileAvatar)
            }

//            storage.setProfilePicture(recipientAddress, profileAvatar, profileKey)
        }
        catch (e: NonRetryableException){
            Log.e("Loki", "Failed to download profile avatar from non-retryable error", e)
            errorUrls += profileAvatar
            return delegate.handleJobFailedPermanently(this, dispatcherName, e)
        }
        catch (e: Exception) {
            if(e is HTTP.HTTPRequestFailedException && e.statusCode == 404){
                Log.e("Loki", "Failed to download profile avatar from non-retryable error", e)
                errorUrls += profileAvatar
                return delegate.handleJobFailedPermanently(this, dispatcherName, e)
            } else {
                Log.e("Loki", "Failed to download profile avatar", e)
                if (failureCount + 1 >= maxFailureCount) {
                    errorUrls += profileAvatar
                }
                return delegate.handleJobFailed(this, dispatcherName, e)
            }
        }
        return delegate.handleJobSucceeded(this, dispatcherName)
    }

    override fun serialize(): Data {
        val data = Data.Builder()
            .putString(PROFILE_AVATAR_KEY, profileAvatar)
            .putString(RECEIPIENT_ADDRESS_KEY, recipientAddress.toString())

        if (profileKey != null) {
            data.putByteArray(PROFILE_KEY, profileKey)
        }

        return data.build()
    }

    override fun getFactoryKey(): String {
        return KEY
    }

    class Factory: Job.Factory<RetrieveProfileAvatarJob> {
        override fun create(data: Data): RetrieveProfileAvatarJob {
            val profileAvatar = if (data.hasString(PROFILE_AVATAR_KEY)) { data.getString(PROFILE_AVATAR_KEY) } else { null }
            val recipientAddress = Address.fromSerialized(data.getString(RECEIPIENT_ADDRESS_KEY))
            val profileKey = data.getByteArray(PROFILE_KEY)
            return RetrieveProfileAvatarJob(profileAvatar, recipientAddress, profileKey)
        }
    }
}