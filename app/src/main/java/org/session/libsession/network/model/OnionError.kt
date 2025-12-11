package org.session.libsession.network.model

import org.session.libsignal.utilities.Snode

sealed class OnionError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * We couldn't even talk to the guard node.
     * Typical causes: offline, DNS failure, TCP connect fails, TLS failure.
     */
    data class GuardConnectionFailed(
        val guard: Snode,
        val underlying: Throwable
    ) : OnionError("Failed to connect to guard ${guard.ip}:${guard.port}", underlying)

    /**
     * Guard responded with a valid HTTP response but rejected the onion request as such.
     * E.g. 4xx/5xx from the guard itself, protocol mismatch, overloaded, etc.
     */
    data class GuardProtocolError(
        val guard: Snode?,
        val code: Int,
        val body: String?
    ) : OnionError("Guard ${guard?.ip}:${guard?.port} rejected onion request with $code", null)

    /**
     * The onion chain broke mid-path: one hop reported that the next node was not found.
     * failedPublicKey is the ed25519 key of the missing snode if known.
     */
    data class IntermediateNodeFailed(
        val reportingNode: Snode?,
        val failedPublicKey: String?
    ) : OnionError("Intermediate node failure (failedPublicKey=$failedPublicKey)", null)

    /**
     * The exit node tried to reach the destination (server or snode) but failed at the network layer.
     * DNS failure, connection refused, timeout, etc.
     */
    data class DestinationUnreachable(
        val exitNode: Snode?,
        val destination: String,
        val underlying: Throwable?
    ) : OnionError("Exit node could not reach destination $destination", underlying)

    /**
     * The destination (server or snode) responded with a non-success application-level status.
     * E.g. 404, 401, 500, app-specific error JSON, etc.
     * This means the path worked; usually we don't penalize the path.
     */
    data class DestinationError(
        val code: Int,
        val body: String?
    ) : OnionError("Destination returned error $code", null)

    /**
     * Clock out of sync with the snode network (your special 406/425 cases).
     */
    data class ClockOutOfSync(
        val code: Int,
        val body: String?
    ) : OnionError("Clock out of sync with service node network (code=$code)", null)

    /**
     * The guard/destination returned something that we couldn't decode as a valid onion response.
     */
    data class InvalidResponse(
        val raw: ByteArray
    ) : OnionError("Invalid onion response", null)

    /**
     * Fallback for anything we haven't classified yet.
     */
    data class Unknown(
        val underlying: Throwable
    ) : OnionError("Unknown onion error", underlying)
}
