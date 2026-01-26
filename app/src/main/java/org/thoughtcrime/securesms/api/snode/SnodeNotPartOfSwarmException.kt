package org.thoughtcrime.securesms.api.snode

import org.session.libsignal.utilities.Snode

class SnodeNotPartOfSwarmException(val responseBodyText: String, val snode: Snode)
    : RuntimeException("Snode is not part of the swarm")