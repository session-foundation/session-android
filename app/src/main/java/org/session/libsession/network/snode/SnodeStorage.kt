package org.session.libsession.network.snode


import org.session.libsession.network.model.Path
import org.session.libsignal.utilities.Snode

interface SnodePathStorage {
    fun getOnionRequestPaths(): List<Path>
    fun setOnionRequestPaths(paths: List<Path>)
    fun clearOnionRequestPaths()
}

interface SwarmStorage {
    fun getSwarm(publicKey: String): Set<Snode>
    fun setSwarm(publicKey: String, swarm: Set<Snode>)
}

interface SnodePoolStorage {
    fun getSnodePool(): Set<Snode>
    fun setSnodePool(newValue: Set<Snode>)
}