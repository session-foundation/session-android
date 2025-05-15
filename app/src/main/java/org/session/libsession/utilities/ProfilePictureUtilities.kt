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

    @OptIn(DelicateCoroutinesApi::class)
    fun resubmitProfilePictureIfNeeded(context: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            // Files expire on the file server after a while, so we simply re-upload the user's profile picture
            // at a certain interval to ensure it's always available.
            val userPublicKey = getLocalNumber(context) ?: return@launch
            val now = Date().time
            val lastProfilePictureUpload = getLastProfilePictureUpload(context)
            if (now - lastProfilePictureUpload <= 14 * 24 * 60 * 60 * 1000) return@launch

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
        var id: Long = 0

        // this can throw an error
        id = retryIfNeeded(4) {
            FileServerApi.upload(data)
        }.await()

        TextSecurePreferences.setLastProfilePictureUpload(context, Date().time)
        val url = "${FileServerApi.server}/file/$id"
        TextSecurePreferences.setProfilePictureURL(context, url)

        return url
    }
}