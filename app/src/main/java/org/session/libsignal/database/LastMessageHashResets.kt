package org.session.libsignal.database

/**
 * A point in the sequence of cursor resets, taken when a poll starts. See
 * [LokiAPIDatabaseProtocol.setLastMessageHashValue].
 */
@JvmInline
value class LastMessageHashEpoch internal constructor(internal val resets: Long)

/**
 * Keeps a poll that was in flight when a swarm's cursors were reset from writing its position back.
 *
 * Counted rather than compared by value. A cursor that was already empty before the poll reads the same
 * before and after a reset, so comparing values would let that poll write its position back and undo it.
 *
 * Every reset and every guarded write runs under this object's lock, together with its storage operation, so
 * a reset cannot land between a write's check and the write itself.
 */
class LastMessageHashResets {
    private var resets = 0L
    private val lastResetOf = HashMap<String, Long>()
    private var lastResetOfAll = 0L

    @Synchronized
    fun epoch(): LastMessageHashEpoch = LastMessageHashEpoch(resets)

    /** Resets the cursors of [publicKey], or of every swarm when it is null, by running [clear]. */
    @Synchronized
    fun reset(publicKey: String?, clear: () -> Unit) {
        resets++
        if (publicKey == null) {
            lastResetOfAll = resets
        } else {
            lastResetOf[publicKey] = resets
        }
        clear()
    }

    /** Runs [write] unless [publicKey]'s cursors were reset after [since]. Returns whether it ran. */
    @Synchronized
    fun writeUnlessResetSince(publicKey: String, since: LastMessageHashEpoch, write: () -> Unit): Boolean {
        val lastReset = maxOf(lastResetOf[publicKey] ?: 0L, lastResetOfAll)
        if (lastReset > since.resets) return false

        write()
        return true
    }
}
