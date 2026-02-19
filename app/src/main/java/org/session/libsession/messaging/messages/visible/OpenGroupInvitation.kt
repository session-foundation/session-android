package org.session.libsession.messaging.messages.visible

import androidx.annotation.Keep
import org.session.protos.SessionProtos
import org.session.libsignal.utilities.Log

// R8: Must keep constructor for Kryo to work
@Keep
class OpenGroupInvitation() {
    var url: String? = null
    var name: String? = null

    fun isValid(): Boolean {
        return (url != null && name != null)
    }

    companion object {
        const val TAG = "OpenGroupInvitation"

        fun fromProto(proto: SessionProtos.DataMessage.OpenGroupInvitation): OpenGroupInvitation {
            return OpenGroupInvitation(proto.url, proto.name)
        }
    }

    constructor(url: String?, serverName: String?): this() {
        this.url = url
        this.name = serverName
    }

    fun toProto(): SessionProtos.DataMessage.OpenGroupInvitation? {
        val openGroupInvitationProto = SessionProtos.DataMessage.OpenGroupInvitation.newBuilder()
        openGroupInvitationProto.url = url
        openGroupInvitationProto.name = name
        return try {
            openGroupInvitationProto.build()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't construct open group invitation proto from: $this.")
            null
        }
    }
}