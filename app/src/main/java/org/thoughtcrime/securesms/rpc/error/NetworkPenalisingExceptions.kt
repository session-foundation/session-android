package org.thoughtcrime.securesms.rpc.error

import org.session.libsession.network.model.Path
import org.session.libsignal.utilities.Snode

/**
 * Marker interface for errors that should penalise the path they occurred on.
 */
interface PathPenalisingException {
    val offendingPath: Path
}

/**
 * Marker interface for errors that should penalise a specific snode.
 */
interface SnodePenalisingException {
    val offendingSnodeED25519PubKey: String
    val offendingSnode: Snode?
}
