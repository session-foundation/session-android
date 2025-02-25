package org.session.libsession.messaging.utilities

import com.google.protobuf.ByteString
import org.session.libsignal.utilities.Log
import org.session.libsignal.protos.SignalServiceProtos.Envelope
import org.session.libsignal.protos.WebSocketProtos.WebSocketMessage
import org.session.libsignal.protos.WebSocketProtos.WebSocketRequestMessage
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
     * Wraps `content` in a `SignalServiceProtos.Envelope`, luego en un `WebSocketProtos.WebSocketMessage`.
     *
     * @param type El tipo de Envelope (SESSION_MESSAGE, CLOSED_GROUP_MESSAGE, etc.)
     * @param timestamp Campo required en Envelope.
     * @param senderPublicKey Se asigna a Envelope.source
     * @param content Bytes cifrados que van en Envelope.content
     * @param threadKeyAlias Opcional: se pondrá en Envelope.thread_key_alias
     *
     * @return Un ByteArray que es un WebSocketMessage serializado, con Envelope adentro.
     */
    fun wrap(
        type: Envelope.Type,
        timestamp: Long,
        senderPublicKey: String,
        content: ByteArray,
        threadKeyAlias: String? = null
    ): ByteArray {
        try {
            Log.d("MessageWrapper", "wrap() => type=$type, timestamp=$timestamp, senderPK=$senderPublicKey, threadAlias=$threadKeyAlias")
            val envelope = createEnvelope(type, timestamp, senderPublicKey, content, threadKeyAlias)
            val webSocketMessage = createWebSocketMessage(envelope)
            return webSocketMessage.toByteArray()
        } catch (e: Exception) {
            Log.w("MessageWrapper", "Failed to wrap data: ${e.message}")
            throw if (e is Error) e else Error.FailedToWrapData
        }
    }

    private fun createEnvelope(
        type: Envelope.Type,
        timestamp: Long,
        senderPublicKey: String,
        content: ByteArray,
        threadKeyAlias: String?
    ): Envelope {
        try {
            val builder = Envelope.newBuilder()
            builder.type = type                       // required
            builder.timestamp = timestamp             // required
            builder.source = senderPublicKey          // opcional, pero aquí se usa
            builder.sourceDevice = 1                  // si lo necesitas
            builder.content = ByteString.copyFrom(content)

            // Si hay threadKeyAlias, lo asignamos
            if (!threadKeyAlias.isNullOrEmpty()) {
                builder.threadKeyAlias = threadKeyAlias
                Log.d("MessageWrapper", "createEnvelope() => set threadKeyAlias=$threadKeyAlias")
            }

            val envelope = builder.build()
            Log.d("MessageWrapper", "createEnvelope() => Envelope listo con type=$type, timestamp=$timestamp.")
            return envelope
        } catch (e: Exception) {
            Log.d("MessageWrapper", "Failed to wrap message in envelope: ${e.message}.")
            throw Error.FailedToWrapMessageInEnvelope
        }
    }

    private fun createWebSocketMessage(envelope: Envelope): WebSocketMessage {
        try {
            val requestId = SecureRandom.getInstance("SHA1PRNG").nextLong()
            val wsRequest = WebSocketRequestMessage.newBuilder().apply {
                verb = "PUT"
                path = "/api/v1/message"
                id = requestId
                body = envelope.toByteString()
            }.build()

            val wsMessage = WebSocketMessage.newBuilder().apply {
                request = wsRequest
                type = WebSocketMessage.Type.REQUEST
            }.build()

            Log.d("MessageWrapper", "createWebSocketMessage() => se armó WebSocketMessage con requestId=$requestId")
            return wsMessage
        } catch (e: Exception) {
            Log.d("MessageWrapper", "Failed to wrap envelope in web socket message: ${e.message}.")
            throw Error.FailedToWrapEnvelopeInWebSocketMessage
        }
    }
    // endregion

    // region Unwrapping
    /**
     * Deserializa un WebSocketMessage y extrae el Envelope interno.
     * `data` no debe venir en base64 (ya decodificado).
     */
    fun unwrap(data: ByteArray): Envelope {
        try {
            Log.d("MessageWrapper", "unwrap() => Vamos a parsear WebSocketMessage + Envelope.")
            val webSocketMessage = WebSocketMessage.parseFrom(data)
            val envelopeAsData = webSocketMessage.request.body
            val envelope = Envelope.parseFrom(envelopeAsData)
            Log.d("MessageWrapper", "unwrap() => Extraído Envelope con type=${envelope.type} timestamp=${envelope.timestamp}")
            return envelope
        } catch (e: Exception) {
            Log.d("MessageWrapper", "Failed to unwrap data: ${e.message}.")
            throw Error.FailedToUnwrapData
        }
    }
    // endregion
}
