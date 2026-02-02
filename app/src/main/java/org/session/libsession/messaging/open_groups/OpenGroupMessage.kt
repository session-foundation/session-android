package org.session.libsession.messaging.open_groups

import org.session.libsignal.crypto.PushTransportDetails
import org.session.libsignal.utilities.Base64.decode
import org.session.protos.SessionProtos

data class OpenGroupMessage(
    val serverID: Long? = null,
    val sender: String?,
    val sentTimestamp: Long,
    /**
     * The serialized protobuf in base64 encoding.
     */
    val base64EncodedData: String?,
    /**
     * When sending a message, the sender signs the serialized protobuf with their private key so that
     * a receiving user can verify that the message wasn't tampered with.
     */
    val base64EncodedSignature: String? = null,
    val reactions: Map<String, OpenGroupApi.Reaction>? = null
) {
    fun toProto(): SessionProtos.Content {
        val data = decode(base64EncodedData).let(PushTransportDetails::getStrippedPaddingMessageBody)
        return SessionProtos.Content.parseFrom(data)
    }
}