package org.thoughtcrime.securesms.database

import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.util.asSequence

fun SmsDatabase.updateThreadId(fromId: Long, toId: Long) {
    if (fromId == toId) return

    //language=roomsql
    val updatedMessageIds = writableDatabase.query("""
       UPDATE ${SmsDatabase.TABLE_NAME}
       SET ${SmsDatabase.THREAD_ID} = ?
       WHERE ${SmsDatabase.THREAD_ID} = ?
       RETURNING ${SmsDatabase.ID}
    """, arrayOf(toId, fromId)).use { cursor ->
        cursor.asSequence().map { MessageId(it.getLong(0), false) }.toList()
    }

    if (updatedMessageIds.isNotEmpty()) {
        updateNotification.tryEmit(MessageUpdateNotification(
            changeType = MessageUpdateNotification.ChangeType.Deleted,
            ids = updatedMessageIds,
            threadId = fromId
        ))

        updateNotification.tryEmit(MessageUpdateNotification(
            changeType = MessageUpdateNotification.ChangeType.Added,
            ids = updatedMessageIds,
            threadId = toId
        ))
    }
}