package org.thoughtcrime.securesms.util

import org.thoughtcrime.securesms.database.model.MessageRecord

// Functions to generate a unique message ID based on the existing message ID and whether this is a SMS or MMS message
object MessageUtils {
    private const val TAG = "MessageUtils"

    // MMS messages have the 63rd bit set, which partitions the long in half (the lower half for
    // SMS messages and the higher half for MMS messages). Each half has space to uniquely identify
    // over 4 quintillion messages.
    private const val MMS_BIT_MASK = 1L shl 62

    // Generate a unique ID for a message record based on its current SMS or MMS ID and the MMS bit mask if appropriate
    fun generateUniqueId(messageRecord: MessageRecord): Long {
        return messageRecord.id or (if (messageRecord.isMms) MMS_BIT_MASK else 0L)
    }

    // Careful: The unique message ID must be one generated as per `generateUniqueId` above - a
    // standard MMS MessageRecord id will always return false to this check!
    fun isMms(uniqueMessageId: Long): Boolean {
        return (uniqueMessageId and MMS_BIT_MASK) != 0L // Check if the 63rd bit is set
    }
}
