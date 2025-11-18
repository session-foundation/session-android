package org.session.libsession.messaging.messages.visible

import com.google.protobuf.ByteString
import org.session.libsignal.utilities.Log
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.LokiProfile
import org.thoughtcrime.securesms.util.DateUtils.Companion.asEpochMillis
import org.thoughtcrime.securesms.util.DateUtils.Companion.asEpochSeconds
import org.thoughtcrime.securesms.util.DateUtils.Companion.millsToInstant
import org.thoughtcrime.securesms.util.DateUtils.Companion.secondsToInstant
import org.thoughtcrime.securesms.util.DateUtils.Companion.toEpochSeconds
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

    fun toProto(builder: SignalServiceProtos.DataMessage.Builder) {
        val displayName = displayName
        if (displayName == null) {
            Log.w(TAG, "Couldn't construct profile proto from: $this")
            return
        }

        val profileProto = builder.profileBuilder
            .setDisplayName(displayName)

        profileKey?.let { builder.profileKey = ByteString.copyFrom(it) }
        profilePictureURL?.let { profileProto.profilePicture = it }
        profileUpdated?.let { profileProto.lastProfileUpdateSeconds = it.toEpochSeconds() }
    }
}