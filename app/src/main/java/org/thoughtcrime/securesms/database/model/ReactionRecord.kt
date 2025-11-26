package org.thoughtcrime.securesms.database.model

data class ReactionRecord(
    val id: Long = 0,
    val messageId: MessageId,
    val author: String,
    val emoji: String,
    val serverId: String = "",
    /**
     * Count of this emoji per message. Note that this means that multiple rows with the same
     * messageId and emoji will have the same count value (due to having different author).
     *
     * So you MUST NOT sum these counts across rows for one message.
     */
    val count: Long = 0,
    val sortId: Long = 0,
    val dateSent: Long = 0,
    val dateReceived: Long = 0
)