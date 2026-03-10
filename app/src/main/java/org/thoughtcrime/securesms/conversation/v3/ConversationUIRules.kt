package org.thoughtcrime.securesms.conversation.v3

import org.thoughtcrime.securesms.conversation.v3.compose.message.ClusterPosition
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.util.DateUtils
import kotlin.math.abs

/**
 * Helper class for UI rules in the conversation
 */
internal object ConversationUIRules {
    fun clusterPosition(
        current: MessageRecord,
        newer: MessageRecord?,
        older: MessageRecord?,
        isGroupThread: Boolean,
        dateUtils: DateUtils,
    ): ClusterPosition {
        val isTop = older == null || !belongsToSameVisualCluster(current, older, isGroupThread, dateUtils)
        val isBottom = newer == null || !belongsToSameVisualCluster(current, newer, isGroupThread, dateUtils)

        return when {
            isTop && isBottom -> ClusterPosition.ISOLATED
            isTop -> ClusterPosition.TOP
            isBottom -> ClusterPosition.BOTTOM
            else -> ClusterPosition.MIDDLE
        }
    }

    fun shouldShowDateBreakAbove(
        current: MessageRecord,
        older: MessageRecord?,
        dateUtils: DateUtils,
    ): Boolean {
        if (older == null) return true

        val currentTimestamp = current.timestamp
        val olderTimestamp = older.timestamp

        if (abs(currentTimestamp - olderTimestamp) > FIVE_MINUTES_MS) return true

        return !dateUtils.isSameDay(currentTimestamp, olderTimestamp)
    }

    fun shouldShowAuthorNameAbove(
        current: MessageRecord,
        older: MessageRecord?,
        isGroupThread: Boolean,
        showDateBreakAbove: Boolean,
    ): Boolean {
        if (!isGroupThread) return false
        if (current.isOutgoing) return false

        return showDateBreakAbove ||
            older?.isControlMessage == true ||
            current.individualRecipient.address != older?.individualRecipient?.address
    }

    private fun belongsToSameVisualCluster(
        current: MessageRecord,
        neighbor: MessageRecord,
        isGroupThread: Boolean,
        dateUtils: DateUtils,
    ): Boolean {
        if (neighbor.isControlMessage) return false
        if (!dateUtils.isSameHour(current.timestamp, neighbor.timestamp)) return false

        return if (isGroupThread) {
            current.individualRecipient.address == neighbor.individualRecipient.address
        } else {
            current.isOutgoing == neighbor.isOutgoing
        }
    }

    private const val FIVE_MINUTES_MS = 5 * 60 * 1000L
}
