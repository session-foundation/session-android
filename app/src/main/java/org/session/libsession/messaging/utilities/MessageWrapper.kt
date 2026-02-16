package org.session.libsession.messaging.utilities

import org.session.libsignal.protos.WebSocketProtos
import org.session.protos.SessionProtos.Envelope

object MessageWrapper {

    fun unwrap(data: ByteArray): Envelope {
        val webSocketMessage = WebSocketProtos.WebSocketMessage.parseFrom(data)
        val envelopeAsData = webSocketMessage.request.body
        return Envelope.parseFrom(envelopeAsData)
    }
    // endregion
}
