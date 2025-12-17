package org.session.libsession.network.snode


import org.session.libsession.network.model.Path
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.database.LokiAPIDatabase
import javax.inject.Inject
import javax.inject.Singleton

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


@Singleton
class DbSnodePathStorage @Inject constructor(private val db: LokiAPIDatabase) : SnodePathStorage {
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

@Singleton
class DbSwarmStorage @Inject constructor(private val db: LokiAPIDatabase) : SwarmStorage {
    override fun getSwarm(publicKey: String): Set<Snode> {
        return db.getSwarm(publicKey) ?: emptySet() // Handle potential null return
    }

    override fun setSwarm(publicKey: String, swarm: Set<Snode>) {
        db.setSwarm(publicKey, swarm)
    }
}

@Singleton
class DbSnodePoolStorage @Inject constructor(private val db: LokiAPIDatabase) : SnodePoolStorage {
    override fun getSnodePool(): Set<Snode> {
        return db.getSnodePool()
    }

    override fun setSnodePool(newValue: Set<Snode>) {
        db.setSnodePool(newValue)
    }
}