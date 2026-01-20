package org.thoughtcrime.securesms.api.swarm

import org.session.libsignal.utilities.Snode

sealed interface SwarmApiError {
    class SnodeNotLongerPartOfSwarmError(
        val snode: Snode,
        val swarmPubKeyHex: String
    ) : SwarmApiError, RuntimeException("Snode is no longer part of the swarm")
}