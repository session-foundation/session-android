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

sealed class OnionError(
    val status: ErrorStatus? = null,
    val destination: OnionDestination?,
    cause: Throwable? = null
) : Exception("Onion error with status code ${status?.code}. Message: ${status?.message}. Destination: ${if(destination is OnionDestination.SnodeDestination) "Snode: "+destination.snode.address else if(destination is OnionDestination.ServerDestination) "Server: "+destination.host else "Unknown"}", cause) {

    /**
     * We got an issue building the path or encoding the payload
     */
    class EncodingError(destination: OnionDestination, cause: Throwable)
        : OnionError(destination = destination, cause = cause)

    /**
     * We couldn't even talk to the guard node.
     * Typical causes: offline, DNS failure, TCP connect fails, TLS failure.
     */
    class GuardUnreachable(val guard: Snode, destination: OnionDestination, cause: Throwable)
        : OnionError(destination = destination, cause = cause)

    /**
     * The onion chain broke mid-path: one hop reported that the next node was not found.
     * failedPublicKey is the ed25519 key of the missing snode if known.
     */
    class IntermediateNodeUnreachable(
        val reportingNode: Snode?,
        status: ErrorStatus,
        val failedPublicKey: String?,
        destination: OnionDestination,
    ) : OnionError(destination = destination, status = status)

    /**
     * The snode reported not being ready
     */
    class SnodeNotReady(
        status: ErrorStatus,
        val failedPublicKey: String?,
        destination: OnionDestination,
    ) : OnionError(destination = destination, status = status)

    /**
     * A snode reported a timeout
     */
    class PathTimedOut(
        status: ErrorStatus,
        destination: OnionDestination,
    ) : OnionError(destination = destination, status = status)

    /**
     * We couldn't reach the destination from the final snode in the path
     */
    class DestinationUnreachable(destination: OnionDestination, status: ErrorStatus)
        : OnionError(destination = destination, status = status)

    /**
     * The error happened, as far as we can tell, along the path on the way to the destination
     */
    class PathError(val node: Snode?, status: ErrorStatus, destination: OnionDestination,)
        : OnionError(status = status, destination = destination)

    /**
     * If we get an invalid response along the path (differs from the InvalidResponse which comes from a 200 payload)
     */
    class InvalidHopResponse(val node: Snode?, status: ErrorStatus, destination: OnionDestination,)
        : OnionError(status = status, destination = destination)

    /**
     * The onion payload returned something that we couldn't decode as a valid onion response.
     */
    class InvalidResponse(destination: OnionDestination, cause: Throwable? = null)
        : OnionError(cause = cause, destination = destination)

    /**
     * Fallback for anything we haven't classified yet.
     */
    class Unknown(destination: OnionDestination, cause: Throwable)
        : OnionError(cause = cause, destination = destination)
}