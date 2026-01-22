package org.thoughtcrime.securesms.api.swarm

import org.session.libsignal.utilities.Snode
import java.util.concurrent.ConcurrentHashMap

class MutableSwarmPool(initialPool: Collection<Snode>) {
    private val concurrentHashMap = ConcurrentHashMap<Snode, Unit>()

    init {
        for (snode in initialPool) {
            concurrentHashMap[snode] = Unit
        }
    }

    fun head(): Snode? {
        TODO()
    }
}