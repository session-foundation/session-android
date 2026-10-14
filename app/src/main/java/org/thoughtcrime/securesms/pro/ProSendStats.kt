package org.thoughtcrime.securesms.pro

import network.loki.messenger.libsession_util.protocol.ProFeature
import network.loki.messenger.libsession_util.protocol.ProMessageFeature
import network.loki.messenger.libsession_util.protocol.ProProfileFeature

/**
 * The "sent" Pro stats — how many Pro badges and how many longer-than-standard messages this account has
 * sent — held as persisted running totals.
 *
 * These used to be a `COUNT(*)` over outgoing message rows carrying the feature bit, which made the stat
 * a view of the message table rather than a record of what the account had done. Two consequences of
 * that, both of which this replaces:
 *
 *  - **Deleting a counted message reduced the stat.** Sending is the event being counted, and deleting
 *    your copy of a message afterwards does not un-send it. Other clients are unaffected by deletion and
 *    this now matches them.
 *  - Every render of the stats screen counted the whole table.
 *
 * The message table is not an input at all — not even to initialise the total. Pro has not shipped, so
 * there are no historical sends to carry over, and a counter that starts at zero is the whole of it.
 *
 * Pure logic over a [Store] so it can be tested without a database; [Store] is implemented by
 * `ProDatabase` over its `pro_state` table.
 */
object ProSendStats {

    /** Persistence for the counters. Values are non-negative and only ever move up. */
    interface Store {
        fun getProStatCount(name: String): Long?

        /** Must be atomic: read-modify-write in one statement, not a get followed by a set. */
        fun incrementProStatCount(name: String)
    }

    /**
     * Record a message reaching "sent" for the first time.
     *
     * Two things have to hold together, and each rules out an obvious simpler place to count:
     *
     *  - **A confirmed send.** Counting when the row is inserted, or at any point before the network
     *    call returns, counts messages that never went anywhere.
     *  - **At most once per message.** Counting on every successful send double-counts a message that
     *    took more than one attempt.
     *
     * [wasAlreadySent] is what reconciles them, and it needs no bookkeeping of its own because the
     * message's own state already carries it. A first success transitions out of a non-sent state, so it
     * counts. A repeat call for a message already recorded as sent does not. A retry after a failure
     * transitions out of *failed*, which is that message's first success, so it counts exactly once.
     */
    fun recordSendSuccess(store: Store, wasAlreadySent: Boolean, features: Set<ProFeature>) {
        if (wasAlreadySent) return
        recordSend(store, features)
    }

    /** Add one to each stat [features] feeds. Prefer [recordSendSuccess]; this does no gating. */
    private fun recordSend(store: Store, features: Set<ProFeature>) {
        features.asSequence()
            .mapNotNull(::storageKey)
            .distinct()
            .forEach(store::incrementProStatCount)
    }

    /**
     * The total to display for [feature] — just what has been counted, with nothing derived from the
     * message rows. Deleting a message you have sent cannot move it, because the rows are not consulted.
     */
    fun total(store: Store, feature: ProFeature): Int {
        val key = storageKey(feature) ?: return 0

        // Saturating rather than wrapping: 2^31 sends is not reachable, but a wrapped Int would display
        // as negative rather than as obviously wrong.
        return (store.getProStatCount(key) ?: 0L)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
    }

    /**
     * Where this feature's running total is stored, or null if it does not feed a stat.
     *
     * Only the two "sent" stats live here. Pinned conversations are derived from config rather than
     * counted, and the groups stat has no data source on any client.
     *
     * These strings are PERSISTED keys. Renaming one abandons the count stored under the old name, so it
     * is a data change rather than an edit.
     */
    private fun storageKey(feature: ProFeature): String? = when (feature) {
        ProProfileFeature.PRO_BADGE -> KEY_BADGES_SENT
        ProMessageFeature.HIGHER_CHARACTER_LIMIT -> KEY_LONG_MESSAGES
        else -> null
    }

    private const val KEY_BADGES_SENT = "pro_stat_badges_sent"
    private const val KEY_LONG_MESSAGES = "pro_stat_long_messages"
}
