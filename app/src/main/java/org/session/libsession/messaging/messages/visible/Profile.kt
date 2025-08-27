package org.session.libsession.messaging.messages.visible

import com.google.protobuf.ByteString
import org.session.libsignal.utilities.Log
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.LokiProfile
import org.thoughtcrime.securesms.util.DateUtils.Companion.asEpochMillis
import org.thoughtcrime.securesms.util.DateUtils.Companion.asEpochSeconds
import org.thoughtcrime.securesms.util.DateUtils.Companion.millsToInstant
import java.time.Instant
import java.time.ZonedDateTime

class Profile(
    var displayName: String? = null,
    var profileKey: ByteArray? = null,
    var profilePictureURL: String? = null,
    var profileUpdated: Instant? = null
) {

    companion object {
        const val TAG = "Profile"

        fun fromProto(proto: SignalServiceProtos.DataMessage): Profile? {
            val profileProto = proto.profile ?: return null
            val displayName = profileProto.displayName ?: return null
            val profileKey = proto.profileKey
            val profilePictureURL = profileProto.profilePicture
            val profileUpdated = profileProto.lastProfileUpdateMs.takeIf {
                profileProto.hasLastProfileUpdateMs()
            }?.millsToInstant()

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
        profileUpdated?.let { profileProto.lastProfileUpdateMs = it.toEpochMilli() }
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