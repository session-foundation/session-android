package org.thoughtcrime.securesms.database

import androidx.collection.MutableLongSet
import org.session.libsession.utilities.Address.Companion.toAddress
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.util.asSequence

fun SmsDatabase.setMessagesRead(where: String, vararg args: Any?): List<MarkedMessageInfo> {
    val updatedThreadIDs = MutableLongSet(1)

    //language=roomsql
    val messages = writableDatabase.rawQuery("""
        UPDATE ${SmsDatabase.TABLE_NAME} 
        SET ${SmsDatabase.READ} = 1
        WHERE $where
        RETURNING ${SmsDatabase.ID}, 
                  ${SmsDatabase.ADDRESS}, 
                  ${SmsDatabase.THREAD_ID}, 
                  ${SmsDatabase.DATE_SENT}, 
                  ${SmsDatabase.EXPIRES_IN},
                  ${SmsDatabase.EXPIRE_STARTED}
    """, *args).use { cursor ->
        cursor.asSequence()
            .map {
                val timestamp = cursor.getLong(3)

                updatedThreadIDs += cursor.getLong(2)

                MarkedMessageInfo(
                    syncMessageId = MessagingDatabase.SyncMessageId(
                        cursor.getString(1).toAddress(),
                        timestamp
                    ),
                    expirationInfo = ExpirationInfo(
                        id = MessageId(cursor.getLong(0), false),
                        timestamp = timestamp,
                        expiresIn = cursor.getLong(4),
                        expireStarted = cursor.getLong(5)
                    )
                )
            }
            .toList()
    }

    updatedThreadIDs.forEach {
        threadDatabase.get().notifyThreadUpdated(it)
    }

    return messages
}