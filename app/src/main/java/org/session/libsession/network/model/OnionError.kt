package org.session.libsession.network.model

import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.rpc.error.PathPenalisingException
import org.thoughtcrime.securesms.rpc.error.SnodePenalisingException

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
        : OnionError(destination = destination, cause = cause), SnodePenalisingException {
        override val offendingSnodeED25519PubKey: String
            get() = guard.publicKeySet!!.ed25519Key

        override val offendingSnode: Snode?
            get() = guard
    }

    /**
     * The onion chain broke mid-path: one hop reported that the next node was not found.
     * failedPublicKey is the ed25519 key of the missing snode if known.
     */
    class IntermediateNodeUnreachable(
        val reportingNode: Snode?,
        status: ErrorStatus,
        override val offendingSnodeED25519PubKey: String,
        destination: OnionDestination,
    ) : OnionError(destination = destination, status = status), SnodePenalisingException {
        override val offendingSnode: Snode?
            get() = null
    }

    /**
     * The snode reported not being ready
     */
    class SnodeNotReady private constructor(
        status: ErrorStatus,
        override val offendingSnodeED25519PubKey: String,
        override val offendingSnode: Snode?,
        destination: OnionDestination,
    ) : OnionError(destination = destination, status = status), SnodePenalisingException {
        constructor(
            status: ErrorStatus,
            offendingSnode: Snode,
            destination: OnionDestination,
        ): this(
            status = status,
            offendingSnodeED25519PubKey = offendingSnode.publicKeySet!!.ed25519Key,
            offendingSnode = offendingSnode,
            destination = destination
        )

        constructor(
            status: ErrorStatus,
            offendingSnodeED25519PubKey: String,
            destination: OnionDestination,
        ): this(
            status = status,
            offendingSnodeED25519PubKey = offendingSnodeED25519PubKey,
            offendingSnode = null,
            destination = destination
        )
    }

    /**
     * A snode reported a timeout
     */
    class PathTimedOut(
        override val offendingPath: Path,
        status: ErrorStatus,
        destination: OnionDestination,
    ) : OnionError(destination = destination, status = status), PathPenalisingException

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
    class InvalidHopResponse(val node: Snode?,
                             override val offendingPath: Path,
                             status: ErrorStatus,
                             destination: OnionDestination,)
        : OnionError(status = status, destination = destination), PathPenalisingException

    /**
     * The error happened after decrypting a payload form the destination
     */
    open class DestinationError(destination: OnionDestination, status: ErrorStatus)
        : OnionError(
        status = status,
        destination = destination
        )

    /**
     * The onion payload returned something that we couldn't decode as a valid onion response.
     */
    class InvalidResponse(destination: OnionDestination, cause: Throwable? = null)
        : OnionError(cause = cause, destination = destination)

    /**
     * Fallback for anything we haven't classified yet.
     */
    class Unknown(destination: OnionDestination?, cause: Throwable)
        : OnionError(cause = cause, destination = destination)
}