package org.thoughtcrime.securesms.database

import network.loki.messenger.libsession_util.protocol.ProFeature
import org.thoughtcrime.securesms.pro.toProMessageFeatures
import org.thoughtcrime.securesms.pro.toProProfileFeatures

/**
 * Reads a message row's Pro "sent" stat state out of the three column values that decide it.
 *
 * Shared by the SMS and MMS tables. Each owns its own cursor — different tables, different column
 * constants — but the two decisions here are NOT duplicated, because both are easy to get subtly wrong and
 * a divergence between the two copies would show up as a stat that is right for text messages and wrong
 * for media, or the reverse.
 */
object ProSendStatRow {

    /** A row that can contribute to the "sent" stats, and whether it already has. */
    data class State(
        val wasAlreadySent: Boolean,
        val features: Set<ProFeature>,
    )

    /**
     * The stat state for a row, or null if the row cannot contribute.
     *
     * Null means INCOMING. Incoming rows carry the sender's feature bits, not ours, so counting one would
     * add someone else's badge to your total. The old whole-table query had the same guard as an
     * `IS_OUTGOING` clause.
     *
     * Call this with values read BEFORE the row is marked sent: [State.wasAlreadySent] is what separates a
     * message's first successful send from a later repeat, and marking it sent destroys that distinction.
     */
    @JvmStatic
    fun from(type: Long, messageFeatures: Long, profileFeatures: Long): State? {
        if (!MmsSmsColumns.Types.isOutgoingMessageType(type)) return null

        val features = mutableSetOf<ProFeature>()
        messageFeatures.toProMessageFeatures(features)
        profileFeatures.toProProfileFeatures(features)

        return State(
            wasAlreadySent = MmsSmsColumns.Types.hasBeenSentSuccessfully(type),
            features = features,
        )
    }
}
