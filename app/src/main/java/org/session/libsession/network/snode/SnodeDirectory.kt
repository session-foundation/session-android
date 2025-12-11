package org.session.libsession.network.snode


import org.session.libsignal.utilities.Snode
import org.session.libsignal.crypto.secureRandom
import org.session.libsignal.utilities.Log

class SnodeDirectory(
    private val storage: SnodePoolStorage,
) {
    fun getSnodePool(): Set<Snode> = storage.getSnodePool()

    fun updateSnodePool(newPool: Set<Snode>) {
        storage.setSnodePool(newPool)
    }

    fun getGuardSnodes(
        existingGuards: Set<Snode>,
        targetGuardCount: Int
    ): Set<Snode> {
        if (existingGuards.size >= targetGuardCount) return existingGuards

        var unused = getSnodePool().minus(existingGuards)
        val needed = targetGuardCount - existingGuards.size

        if (unused.size < needed) {
            throw IllegalStateException("Insufficient snodes to build guards")
        }

        val newGuards = (0 until needed).map {
            val candidate = unused.secureRandom()
            unused = unused - candidate
            Log.d("Onion", "Selected guard snode: $candidate")
            candidate
        }

        return (existingGuards + newGuards).toSet()
    }
}
