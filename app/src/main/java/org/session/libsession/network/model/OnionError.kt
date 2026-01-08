package org.session.libsession.network.model

import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.Snode

data class ErrorStatus(
    val code: Int,
    val message: String? = null,
    val body: ByteArraySlice? = null
) {
    val bodyText: String?
        get() = body?.decodeToString()
}

enum class ErrorOrigin { UNKNOWN, TRANSPORT_TO_GUARD, PATH_HOP, DESTINATION_REPLY }

sealed class OnionError(
    val origin: ErrorOrigin,
    val status: ErrorStatus? = null,
    val snode: Snode? = null,
    cause: Throwable? = null
) : Exception(status?.message ?: "Onion error at ${origin.name}, with status code ${status?.code}. Snode: ${snode?.address}", cause) {

    /**
     * We couldn't even talk to the guard node.
     * Typical causes: offline, DNS failure, TCP connect fails, TLS failure.
     */
    class GuardUnreachable(val guard: Snode, cause: Throwable)
        : OnionError(ErrorOrigin.TRANSPORT_TO_GUARD, cause = cause)

    /**
     * The onion chain broke mid-path: one hop reported that the next node was not found.
     * failedPublicKey is the ed25519 key of the missing snode if known.
     */
    class IntermediateNodeFailed(
        val reportingNode: Snode?,
        val failedPublicKey: String?
    ) : OnionError(origin = ErrorOrigin.PATH_HOP, snode = reportingNode)

    /**
     * The error happened, as far as we can tell, along the path on the way to the destination
     */
    class PathError(val node: Snode?, status: ErrorStatus)
        : OnionError(ErrorOrigin.PATH_HOP, status = status, snode = node)

    /**
     * The error happened after decrypting a payload form the destination
     */
    open class DestinationError(val destination: OnionDestination, status: ErrorStatus)
        : OnionError(
        ErrorOrigin.DESTINATION_REPLY,
        status = status,
        snode = (destination as? OnionDestination.SnodeDestination)?.snode
        )

    /**
     * The onion payload returned something that we couldn't decode as a valid onion response.
     */
    class InvalidResponse(cause: Throwable? = null)
        : OnionError(ErrorOrigin.DESTINATION_REPLY, cause = cause)

    /**
     * Fallback for anything we haven't classified yet.
     */
    class Unknown(cause: Throwable)
        : OnionError(ErrorOrigin.UNKNOWN, cause = cause)
}