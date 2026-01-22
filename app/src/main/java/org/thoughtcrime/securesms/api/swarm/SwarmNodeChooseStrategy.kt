package org.thoughtcrime.securesms.api.swarm

import org.session.libsignal.utilities.Snode

interface SwarmNodeChooseStrategy {
    fun chooseNode(): Snode?
}
