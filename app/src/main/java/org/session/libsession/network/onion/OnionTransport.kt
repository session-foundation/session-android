package org.session.libsession.network.onion

import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionResponse
import org.session.libsignal.utilities.Snode

interface OnionTransport {
    /**
     * Sends an onion request over one path.
     *
     * @return Result.success(response) on success
     *         Result.failure(OnionError) on onion/path/guard/destination error
     */
    suspend fun send(
        path: List<Snode>,
        destination: OnionDestination,
        payload: ByteArray,
        version: Version
    ): Result<OnionResponse>
}

enum class Version(val value: String) {
    V2("/loki/v2/lsrpc"),
    V3("/loki/v3/lsrpc"),
    V4("/oxen/v4/lsrpc");
}
