package org.session.libsession.network.snode


import org.session.libsession.network.model.Path
import org.session.libsignal.utilities.Snode

interface SnodePathStorage {
    fun getOnionRequestPaths(): List<Path>
    fun setOnionRequestPaths(paths: List<Path>)
    fun replaceOnionRequestPath(oldPath: Path, newPath: Path)

    /**
     * Increase strike count for a request path and return the new strike count.
     *
     * @param path The request path to increase the strike count for
     * @param increment The amount to increase the strike count by. Can be negative to decrease
     * strikes.
     * @return The new strike count for the request path if the path exists
     */
    fun increaseOnionRequestPathStrike(path: Path, increment: Int): Int?
    fun increaseOnionRequestPathStrikeContainingSnode(snodeEd25519PubKey: String, increment: Int): Pair<Path, Int>?

    fun findRandomUnusedSnodesForNewPath(n: Int): List<Snode>

    fun clearOnionRequestPaths()
    fun removePath(path: Path)
}

interface SwarmStorage {
    fun getSwarm(publicKey: String): Set<Snode>
    fun setSwarm(publicKey: String, swarm: Set<Snode>)
    fun dropSnodeFromSwarm(publicKey: String, snodeEd25519PubKey: String)
}

interface SnodePoolStorage {
    fun getSnodePool(): List<Snode>
    fun removeSnode(ed25519PubKey: String): Snode?
    fun setSnodePool(newValue: Collection<Snode>)

    fun removeSnodesWithStrikesGreaterThan(n: Int): Int

    /**
     * Increase strike count for a snode and return the new strike count
     *
     * @param snode The snode to increase the strike count for
     * @param increment The amount to increase the strike count by. Can be negative to decrease strikes.
     * @return The new strike count for the snode, or null if the snode does not exist in the pool
     */
    fun increaseSnodeStrike(snode: Snode, increment: Int): Int?
}
