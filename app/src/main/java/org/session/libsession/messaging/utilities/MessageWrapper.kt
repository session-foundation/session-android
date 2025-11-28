package org.session.libsession.messaging.utilities

import org.session.libsignal.protos.SignalServiceProtos.Envelope
import org.session.libsignal.protos.WebSocketProtos.WebSocketMessage

object MessageWrapper {

    fun unwrap(data: ByteArray): Envelope {
        val webSocketMessage = WebSocketMessage.parseFrom(data)
        val envelopeAsData = webSocketMessage.request.body
        return Envelope.parseFrom(envelopeAsData)
    }
    // endregion
}
