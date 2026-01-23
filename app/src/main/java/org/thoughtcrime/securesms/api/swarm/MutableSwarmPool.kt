package org.thoughtcrime.securesms.api.swarm

import org.session.libsignal.utilities.Snode
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class MutableSwarmPool(initialPool: Collection<Snode>) {
    private var snodes = initialPool.shuffled()
    private val readWriteLock = ReentrantReadWriteLock()

    fun head(): Snode? = readWriteLock.read { snodes.firstOrNull() }

    fun replace(newPool: Collection<Snode>): Unit = readWriteLock.write {
        snodes = newPool.shuffled()
    }

    fun drop(snode: Snode): Unit = readWriteLock.write {
        snodes = snodes.filter { it != snode }
    }
}