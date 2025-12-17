package org.session.libsession.network.snode


import org.session.libsession.network.model.Path
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.database.LokiAPIDatabase

//todo ONION  need a hilt module to inject all of these

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


class DbSnodePathStorage(private val db: LokiAPIDatabase) : SnodePathStorage {
    override fun getOnionRequestPaths(): List<Path> {
        return db.getOnionRequestPaths()
    }

    override fun setOnionRequestPaths(paths: List<Path>) {
        db.setOnionRequestPaths(paths)
    }

    override fun clearOnionRequestPaths() {
        db.clearOnionRequestPaths()
    }
}

class DbSwarmStorage(private val db: LokiAPIDatabase) : SwarmStorage {
    override fun getSwarm(publicKey: String): Set<Snode> {
        return db.getSwarm(publicKey) ?: emptySet() // Handle potential null return
    }

    override fun setSwarm(publicKey: String, swarm: Set<Snode>) {
        db.setSwarm(publicKey, swarm)
    }
}

class DbSnodePoolStorage(private val db: LokiAPIDatabase) : SnodePoolStorage {
    override fun getSnodePool(): Set<Snode> {
        return db.getSnodePool()
    }

    override fun setSnodePool(newValue: Set<Snode>) {
        db.setSnodePool(newValue)
    }
}