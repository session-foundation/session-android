package org.session.libsession.network.onion

import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionResponse
import org.session.libsession.network.model.Path
import org.session.libsignal.utilities.Snode

interface OnionTransport {
    /**
     * Sends an onion request over one path.
     *
     */
    suspend fun send(
        path: Path,
        destination: OnionDestination,
        payload: ByteArray,
        version: Version
    ): OnionResponse
}

enum class Version(val value: String) {
    V3("/loki/v3/lsrpc"),
    V4("/oxen/v4/lsrpc");
}
