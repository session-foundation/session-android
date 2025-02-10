package org.session.libsession.messaging.utilities

import com.google.protobuf.ByteString
import org.session.libsignal.protos.SignalServiceProtos.Envelope
import org.session.libsignal.protos.WebSocketProtos.WebSocketMessage
import org.session.libsignal.protos.WebSocketProtos.WebSocketRequestMessage
import org.session.libsignal.utilities.Log
import java.security.SecureRandom

object MessageWrapper {

    // region Types
    sealed class Error(val description: String) : Exception(description) {
        object FailedToWrapData : Error("Failed to wrap data.")
        object FailedToWrapMessageInEnvelope : Error("Failed to wrap message in envelope.")
        object FailedToWrapEnvelopeInWebSocketMessage : Error("Failed to wrap envelope in web socket message.")
        object FailedToUnwrapData : Error("Failed to unwrap data.")
    }
    // endregion

    // region Wrapping
    /**
     * Wraps `message` in a `SignalServiceProtos.Envelope` and then a `WebSocketProtos.WebSocketMessage` to match the desktop application.
     */
    fun wrap(type: Envelope.Type, timestamp: Long, senderPublicKey: String, content: ByteArray): ByteArray {
        try {
            val envelope = createEnvelope(type, timestamp, senderPublicKey, content)
            val webSocketMessage = createWebSocketMessage(envelope)
            return webSocketMessage.toByteArray()
        } catch (e: Exception) {
            throw if (e is Error) e else Error.FailedToWrapData
        }
    }

    fun createEnvelope(type: Envelope.Type, timestamp: Long, senderPublicKey: String, content: ByteArray): Envelope {
        try {
            val builder = Envelope.newBuilder()
            builder.type = type
            builder.timestamp = timestamp
            builder.source = senderPublicKey
            builder.sourceDevice = 1
            builder.content = ByteString.copyFrom(content)
            return builder.build()
        } catch (e: Exception) {
            Log.d("Loki", "Failed to wrap message in envelope: ${e.message}.")
            throw Error.FailedToWrapMessageInEnvelope
        }
    }

    private fun createWebSocketMessage(envelope: Envelope): WebSocketMessage {
        try {
            return WebSocketMessage.newBuilder().apply {
                request = WebSocketRequestMessage.newBuilder().apply {
                    verb = "PUT"
                    path = "/api/v1/message"
                    id = SecureRandom.getInstance("SHA1PRNG").nextLong()
                    body = envelope.toByteString()
                }.build()
                type = WebSocketMessage.Type.REQUEST
            }.build()
        } catch (e: Exception) {
            Log.d("MessageWrapper", "Failed to wrap envelope in web socket message: ${e.message}.")
            throw Error.FailedToWrapEnvelopeInWebSocketMessage
        }
    }
    // endregion

    // region Unwrapping
    /**
     * `data` shouldn't be base 64 encoded.
     */
    fun unwrap(data: ByteArray): Envelope {
        try {
            val webSocketMessage = WebSocketMessage.parseFrom(data)
            val envelopeAsData = webSocketMessage.request.body
            return Envelope.parseFrom(envelopeAsData)
        } catch (e: Exception) {
            Log.d("MessageWrapper", "Failed to unwrap data", e)
            throw Error.FailedToUnwrapData
        }
    }
    // endregion
}
