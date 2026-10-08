package org.session.libsession.messaging.sending_receiving.pollers

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

/**
 * Limits how often a poller asks the swarm to extend the TTL of our config messages.
 *
 * Every extend is a write on every storage node holding those messages, and pollers run every few
 * seconds, so extending on each poll puts real disk I/O load on service nodes for no benefit: the
 * extension is to weeks from now, so doing it once an hour loses nothing.
 *
 * Tracked per swarm, so one group's renewal never suppresses another group's or the user's own.
 */
@Singleton
class ConfigTtlExtensionThrottle(
    private val timeSource: TimeSource.WithComparableMarks,
) {
    @Inject
    constructor() : this(TimeSource.Monotonic)

    private val lastSuccessfulExtension = ConcurrentHashMap<String, ComparableTimeMark>()

    /**
     * Runs [extend] unless an extension for [swarmPubKeyHex] succeeded within [COOLDOWN].
     *
     * The cooldown starts only when [extend] returns normally. A failed extension that started it would
     * leave the configs un-renewed for an hour while looking handled, and repeated failures would let
     * them age out of the swarm — so any exception propagates and the next poll tries again.
     *
     * @return whether [extend] ran.
     */
    suspend fun extendIfDue(swarmPubKeyHex: String, extend: suspend () -> Unit): Boolean {
        val last = lastSuccessfulExtension[swarmPubKeyHex]
        if (last != null && last.elapsedNow() < COOLDOWN) {
            return false
        }

        extend()
        lastSuccessfulExtension[swarmPubKeyHex] = timeSource.markNow()
        return true
    }

    companion object {
        val COOLDOWN = 1.hours
    }
}
