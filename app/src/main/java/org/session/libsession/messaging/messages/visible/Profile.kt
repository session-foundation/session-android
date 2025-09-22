package org.session.libsession.messaging.messages.visible

import com.google.protobuf.ByteString
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.util.DateUtils.Companion.secondsToInstant
import org.thoughtcrime.securesms.util.DateUtils.Companion.toEpochSeconds
import java.time.Instant

class Profile(
    var displayName: String? = null,
    var profileKey: ByteArray? = null,
    var profilePictureURL: String? = null,
    var profileUpdated: Instant? = null
) {

    val userPic: UserPic? get() = profilePictureURL?.let { url ->
        profileKey?.let { key ->
            UserPic(url = url, key = key)
        }
    }

    companion object {
        const val TAG = "Profile"

        fun fromProto(proto: SignalServiceProtos.DataMessage): Profile? {
            val profileProto = proto.profile ?: return null
            val displayName = profileProto.displayName ?: return null
            val profileKey = proto.profileKey
            val profilePictureURL = profileProto.profilePicture
            val profileUpdated = profileProto.lastProfileUpdateSeconds
                .takeIf { profileProto.hasLastProfileUpdateSeconds() }
                ?.secondsToInstant()

            if (profileKey != null && profilePictureURL != null) {
                return Profile(displayName, profileKey.toByteArray(), profilePictureURL, profileUpdated = profileUpdated)
            } else {
                return Profile(displayName, profileUpdated = profileUpdated)
            }
        }
    }

    fun toProto(): SignalServiceProtos.DataMessage? {
        val displayName = displayName
        if (displayName == null) {
            Log.w(TAG, "Couldn't construct profile proto from: $this")
            return null
        }
        val dataMessageProto = SignalServiceProtos.DataMessage.newBuilder()
        val profileProto = SignalServiceProtos.DataMessage.LokiProfile.newBuilder()
        profileProto.displayName = displayName
        profileKey?.let { dataMessageProto.profileKey = ByteString.copyFrom(it) }
        profilePictureURL?.let { profileProto.profilePicture = it }
        profileUpdated?.let { profileProto.lastProfileUpdateSeconds = it.toEpochSeconds() }
        // Build
        try {
            dataMessageProto.profile = profileProto.build()
            return dataMessageProto.build()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't construct profile proto from: $this")
            return null
        }
    }
}