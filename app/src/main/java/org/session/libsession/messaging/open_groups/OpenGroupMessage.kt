package org.session.libsession.messaging.open_groups

import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.open_groups.OpenGroupApi.Capability
import org.session.libsignal.crypto.PushTransportDetails
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Base64.decode
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.removingIdPrefixIfNeeded
import org.session.libsignal.utilities.toHexString

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

    companion object {
        fun fromJSON(json: Map<String, Any>): OpenGroupMessage? {
            val base64EncodedData = json["data"] as? String ?: return null
            val sentTimestamp = json["posted"] as? Double ?: return null
            val serverID = json["id"] as? Int
            val sender = json["session_id"] as? String
            val base64EncodedSignature = json["signature"] as? String
            return OpenGroupMessage(
                serverID = serverID?.toLong(),
                sender = sender,
                sentTimestamp = (sentTimestamp * 1000).toLong(),
                base64EncodedData = base64EncodedData,
                base64EncodedSignature = base64EncodedSignature
            )
        }
    }

    fun sign(server: String): OpenGroupMessage? {
        if (base64EncodedData.isNullOrEmpty()) return null
        val userEdKeyPair = MessagingModuleConfiguration.shared.storage.getUserED25519KeyPair() ?: return null
        val communityServerPubKey = MessagingModuleConfiguration.shared.storage.getOpenGroupPublicKey(server) ?: return null
        val serverCapabilities = MessagingModuleConfiguration.shared.storage.getServerCapabilities(server)
        val signature = if (serverCapabilities?.contains(Capability.BLIND.name.lowercase()) == true) {
            runCatching {
                BlindKeyAPI.blind15Sign(
                    ed25519SecretKey = userEdKeyPair.secretKey.data,
                    serverPubKey = communityServerPubKey,
                    message = decode(base64EncodedData)
                )
            }.onFailure {
                Log.e("OpenGroupMessage", "Failed to sign message with blind key", it)
            }.getOrNull() ?: return null
        }
        else {
            val x25519PublicKey = MessagingModuleConfiguration.shared.storage.getUserX25519KeyPair().publicKey.serialize()
            if (sender != x25519PublicKey.toHexString() && !userEdKeyPair.pubKey.data.toHexString().equals(sender?.removingIdPrefixIfNeeded(), true)) return null
            try {
                ED25519.sign(
                    ed25519PrivateKey = userEdKeyPair.secretKey.data,
                    message = decode(base64EncodedData)
                )
            } catch (e: Exception) {
                Log.w("Loki", "Couldn't sign open group message.", e)
                return null
            }
        }
        return copy(base64EncodedSignature = Base64.encodeBytes(signature))
    }

    fun toJSON(): Map<String, Any?> {
        val json = mutableMapOf( "data" to base64EncodedData, "timestamp" to sentTimestamp )
        serverID?.let { json["server_id"] = it }
        sender?.let { json["public_key"] = it }
        base64EncodedSignature?.let { json["signature"] = it }
        return json
    }

    fun toProto(): SignalServiceProtos.Content {
        val data = decode(base64EncodedData).let(PushTransportDetails::getStrippedPaddingMessageBody)
        return SignalServiceProtos.Content.parseFrom(data)
    }
}