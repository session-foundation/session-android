package org.thoughtcrime.securesms.database.model

data class MessageChanges(
    val changeType: ChangeType,
    val ids: List<MessageId>,
    val threadId: Long,
) {
    constructor(changeType: ChangeType, id: MessageId, threadId: Long)
        :this(
            changeType = changeType,
            ids = listOf(id),
            threadId = threadId
        )

    enum class ChangeType {
        Added,
        Updated,
        Deleted,
    }
}