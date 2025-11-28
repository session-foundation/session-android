package org.thoughtcrime.securesms.database.model

data class ReactionRecord(
    val id: Long = 0,
    val messageId: MessageId,
    val author: String,
    val emoji: String,
    val serverId: String = "",
    /**
     * The meaning of count depends on the context:
     * - When the message is from community, this count represents the total number of reactions of this type and
     *   it is the same across all ReactionRecords for the same emoji/messageId.
     * - When the message is from a private chat, this count should be added up across all ReactionRecords for the
     *   same emoji/messageId to get the total number of reactions of this type.
     */
    val count: Long = 0,
    val sortId: Long = 0,
    val dateSent: Long = 0,
    val dateReceived: Long = 0
)