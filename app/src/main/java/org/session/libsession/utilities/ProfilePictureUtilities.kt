package org.session.libsession.utilities

import android.content.Context
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.UserPic
import okio.Buffer
import org.session.libsession.avatars.AvatarHelper
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.TextSecurePreferences.Companion.getLastProfilePictureUpload
import org.session.libsession.utilities.TextSecurePreferences.Companion.getLocalNumber
import org.session.libsession.utilities.TextSecurePreferences.Companion.getProfileKey
import org.session.libsession.utilities.TextSecurePreferences.Companion.setLastProfilePictureUpload
import org.session.libsignal.streams.DigestingRequestBody
import org.session.libsignal.streams.ProfileCipherOutputStream
import org.session.libsignal.streams.ProfileCipherOutputStreamFactory
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.ProfileAvatarData
import org.session.libsignal.utilities.retryIfNeeded
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Date

object ProfilePictureUtilities {

    const val DEFAULT_AVATAR_TTL: Long = 14 * 24 * 60 * 60 * 1000 // 14 days
    const val DEBUG_AVATAR_TTL: Long = 30 // 30 seconds

    @OptIn(DelicateCoroutinesApi::class)
    fun resubmitProfilePictureIfNeeded(context: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            // Files expire on the file server after a while, so we simply re-upload the user's profile picture
            // at a certain interval to ensure it's always available.
            val userPublicKey = getLocalNumber(context) ?: return@launch
            // no need to go any further if we do not have an avatar
            if(TextSecurePreferences.getProfileAvatarId(context) == 0) return@launch

            val now = Date().time

            val avatarTtl = TextSecurePreferences.getProfileExpiry(context)
            // we can stop here if we have no info on expiry yet
            // We will get that info on upload or download, and the check can happen when we next reopen the app
            if(avatarTtl == 0L) return@launch

            Log.d("Loki-Avatar", "Should reupload avatar? ${now < avatarTtl} (TTL of $avatarTtl)")
            if (now < avatarTtl) return@launch

            // Don't generate a new profile key here; we do that when the user changes their profile picture
            Log.d("Loki-Avatar", "Uploading Avatar Started")
            val encodedProfileKey = getProfileKey(context)
            try {
                // Read the file into a byte array
                val inputStream = AvatarHelper.getInputStreamFor(
                    context,
                    fromSerialized(userPublicKey)
                )
                val baos = ByteArrayOutputStream()
                var count: Int
                val buffer = ByteArray(1024)
                while ((inputStream.read(buffer, 0, buffer.size)
                        .also { count = it }) != -1
                ) {
                    baos.write(buffer, 0, count)
                }
                baos.flush()
                val profilePicture = baos.toByteArray()
                // Re-upload it
                val url = upload(
                    profilePicture,
                    encodedProfileKey!!,
                    context
                )

                // Update the last profile picture upload date
                setLastProfilePictureUpload(
                    context,
                    Date().time
                )

                // update config with new URL for reuploaded file
                val profileKey = ProfileKeyUtil.getProfileKey(context)
                MessagingModuleConfiguration.shared.configFactory.withMutableUserConfigs {
                    it.userProfile.setPic(UserPic(url, Bytes(profileKey)))
                }

                Log.d("Loki-Avatar", "Uploading Avatar Finished")
            } catch (e: Exception) {
                Log.e("Loki-Avatar", "Uploading avatar failed.")
            }
        }
    }

    suspend fun upload(profilePicture: ByteArray, encodedProfileKey: String, context: Context): String {
        val inputStream = ByteArrayInputStream(profilePicture)
        val outputStream =
            ProfileCipherOutputStream.getCiphertextLength(profilePicture.size.toLong())
        val profileKey = ProfileKeyUtil.getProfileKeyFromEncodedString(encodedProfileKey)
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

        // add a custom TTL header if we have enabled it i the debug menu
        val customHeaders = if(TextSecurePreferences.forcedShortTTL(context)){
            mapOf("X-FS-TTL" to DEBUG_AVATAR_TTL.toString()) // force the TTL to 30 seconds
        } else mapOf()

        // this can throw an error
        val result = retryIfNeeded(4) {
            FileServerApi.upload(file = data, customHeaders = customHeaders)
        }.await()

        TextSecurePreferences.setLastProfilePictureUpload(context, Date().time)

        // save the expiry for this profile picture, so that whe we periodically check if we should
        // reupload, we can check against this timestamp
        updateAvatarExpiryTimestamp(context, result.ttlTimestamp)

        val url = "${FileServerApi.FILE_SERVER_URL}/file/${result.id}"
        TextSecurePreferences.setProfilePictureURL(context, url)

        return url
    }

    fun updateAvatarExpiryTimestamp(context: Context, expiry: Long?){
        TextSecurePreferences.setProfileExpiry(
            context,
            expiry ?: DEFAULT_AVATAR_TTL
        )
    }
}