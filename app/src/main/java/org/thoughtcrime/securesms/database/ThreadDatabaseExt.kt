package org.thoughtcrime.securesms.database

import android.database.sqlite.SQLiteDoneException
import androidx.collection.LongLongMap
import androidx.collection.MutableLongLongMap
import androidx.collection.MutableLongSet
import androidx.collection.mutableLongSetOf
import androidx.core.database.getStringOrNull
import androidx.sqlite.db.transaction
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.recipients.RecipientData
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.util.asSequence
import kotlin.time.Instant

fun ThreadDatabase.queryThreads(addresses: Collection<Address.Conversable>): List<ThreadRecord> {
    val addressAsJson = json.encodeToString(addresses)

    //language=roomsql
    return readableDatabase.query(
        """
            SELECT 
            ${ThreadDatabase.ID},
            ${ThreadDatabase.ADDRESS},
            
            -- Query the groupInviteTable to find out who invited the user to this group
            (SELECT ${LokiMessageDatabase.invitingSessionId} FROM ${LokiMessageDatabase.groupInviteTable} WHERE ${LokiMessageDatabase.threadID} = threads.${ThreadDatabase.ID} LIMIT 1) AS invitingAdminId,
            
            -- Count unread sms
            (
                SELECT COUNT(*) 
                FROM ${SmsDatabase.TABLE_NAME} s 
                WHERE s.${SmsDatabase.THREAD_ID} = threads.${ThreadDatabase.ID} 
                    AND ${SmsDatabase.DATE_SENT} > ${ThreadDatabase.LAST_SEEN} 
                    AND NOT s.${MmsSmsColumns.IS_OUTGOING}
                    AND NOT s.${MmsSmsColumns.IS_DELETED}
            ) AS smsUnreadCount,
            
            -- Count unread sms with mention
            (
                SELECT COUNT(*) 
                FROM ${SmsDatabase.TABLE_NAME} s 
                WHERE s.${SmsDatabase.THREAD_ID} = threads.${ThreadDatabase.ID} 
                    AND ${SmsDatabase.DATE_SENT} > ${ThreadDatabase.LAST_SEEN}
                    AND s.${SmsDatabase.HAS_MENTION}
                    AND NOT s.${MmsSmsColumns.IS_OUTGOING}
                    AND NOT s.${MmsSmsColumns.IS_DELETED}
            ) AS smsUnreadMentionCount,
            
            -- Count unread mms
            (
                SELECT COUNT(*) 
                FROM ${MmsDatabase.TABLE_NAME} m 
                WHERE m.${MmsSmsColumns.THREAD_ID} = threads.${ThreadDatabase.ID} 
                    AND ${MmsDatabase.DATE_SENT} > ${ThreadDatabase.LAST_SEEN}
                    AND NOT m.${MmsSmsColumns.IS_OUTGOING}
                    AND NOT m.${MmsSmsColumns.IS_DELETED}
            ) AS mmsUnreadCount,
            
            -- Count unread mms with mention
            (
                SELECT COUNT(*) 
                FROM ${MmsDatabase.TABLE_NAME} m 
                WHERE m.${MmsSmsColumns.THREAD_ID} = threads.${ThreadDatabase.ID} 
                    AND ${MmsDatabase.DATE_SENT} > ${ThreadDatabase.LAST_SEEN}
                    AND m.${MmsSmsColumns.HAS_MENTION}
                    AND NOT m.${MmsSmsColumns.IS_OUTGOING}
                    AND NOT m.${MmsSmsColumns.IS_DELETED}
            ) AS mmsUnreadMentionCount,
            
             -- Count sms
            (
                SELECT COUNT(*) 
                FROM ${SmsDatabase.TABLE_NAME} s 
                WHERE s.${SmsDatabase.THREAD_ID} = threads.${ThreadDatabase.ID} 
            ) AS smsCount,
            
            -- Count mms
            (
                SELECT COUNT(*) 
                FROM ${MmsDatabase.TABLE_NAME} m 
                WHERE m.${MmsSmsColumns.THREAD_ID} = threads.${ThreadDatabase.ID} 
            ) AS mmsCount
        FROM ${ThreadDatabase.TABLE_NAME} AS threads
        WHERE ${ThreadDatabase.ADDRESS} IN (SELECT value FROM json_each(?))
    """, arrayOf(addressAsJson)).use { cursor ->
        cursor.asSequence()
            .mapTo(ArrayList(cursor.count)) { cursor ->
                val threadId = cursor.getLong(0)
                val threadAddress = cursor.getString(1).toAddress() as Address.Conversable
                val invitingAdminId = cursor.getStringOrNull(2)
                val smsUnreadCount = cursor.getLong(3)
                val smsUnreadMentionCount = cursor.getLong(4)
                val mmsUnreadCount = cursor.getLong(5)
                val mmsUnreadMentionCount = cursor.getLong(6)
                val smsCount = cursor.getLong(7)
                val mmsCount = cursor.getLong(8)

                val threadRecipient = recipientRepository.get().getRecipientSync(threadAddress)
                val lastMessage = mmsSmsDatabase.get().getLastMessage(
                    /* threadId = */ threadId,
                    /* includeReactions = */ false,
                    /* getQuote = */ false
                )

                val date = when {
                    lastMessage != null -> lastMessage.dateReceived
                    threadRecipient.data is RecipientData.Contact -> threadRecipient.data.createdAt.toEpochMilli()
                    threadRecipient.data is RecipientData.Group -> threadRecipient.data.joinedAt.toEpochMilli()
                    else -> 0L
                }

                ThreadRecord(
                    threadId = threadId,
                    recipient = threadRecipient,
                    lastMessage = lastMessage,
                    count = smsCount.toInt() + mmsCount.toInt(),
                    unreadCount = smsUnreadCount.toInt() + mmsUnreadCount.toInt(),
                    unreadMentionCount = smsUnreadMentionCount.toInt() + mmsUnreadMentionCount.toInt(),
                    isUnread = false, // This information is not stored in the db, you need to populate it from config
                    date = date,
                    invitingAdminId = invitingAdminId
                )
            }
    }
}

fun ThreadDatabase.threadContainsOutgoingMessage(threadId: Long): Boolean {
    //language=roomsql
    val hasOutgoingSms = readableDatabase.rawQuery("""
        SELECT 1 FROM ${SmsDatabase.TABLE_NAME}
        WHERE ${SmsDatabase.THREAD_ID} = ?
          AND ${SmsDatabase.IS_OUTGOING}
          AND NOT ${MmsSmsColumns.IS_DELETED}
        LIMIT 1
    """, threadId).use { it.count > 0 }

    if (hasOutgoingSms) return true

    //language=roomsql
    return readableDatabase.rawQuery("""
        SELECT 1 FROM ${MmsDatabase.TABLE_NAME}
        WHERE ${MmsSmsColumns.THREAD_ID} = ?
          AND ${MmsSmsColumns.IS_OUTGOING}
          AND NOT ${MmsSmsColumns.IS_DELETED}
        LIMIT 1
    """, threadId).use { it.count > 0 }
}

fun ThreadDatabase.getLastSeen(address: Address.Conversable): Instant? {
    return readableDatabase.query(
        "SELECT ${ThreadDatabase.LAST_SEEN} FROM ${ThreadDatabase.TABLE_NAME} WHERE ${ThreadDatabase.ADDRESS} = ?",
        arrayOf(address.address)
    ).use { cursor ->
        if (cursor.moveToNext()) {
            Instant.fromEpochMilliseconds(cursor.getLong(0))
        } else {
            null
        }
    }
}

fun ThreadDatabase.getLastSeen(id: Long): Instant? {
    return readableDatabase.query(
        "SELECT ${ThreadDatabase.LAST_SEEN} FROM ${ThreadDatabase.TABLE_NAME} WHERE ${ThreadDatabase.ID} = ?",
        arrayOf(id)
    ).use {
        if (it.moveToNext()) {
            Instant.fromEpochMilliseconds(it.getLong(0))
        } else {
            null
        }
    }
}

fun ThreadDatabase.getAllLastSeen(): LongLongMap {
    return readableDatabase.query(
        "SELECT ${ThreadDatabase.ID}, ${ThreadDatabase.LAST_SEEN} FROM ${ThreadDatabase.TABLE_NAME}"
    ).use { cursor ->
        MutableLongLongMap(cursor.count).apply {
            while (cursor.moveToNext()) {
                set(cursor.getLong(0), cursor.getLong(1))
            }
        }
    }
}

/**
 * Update or create a thread record to store the given lastRead timestamp.
 */
fun ThreadDatabase.upsertThreadLastSeen(lastReads: Iterable<Pair<Address.Conversable, Instant>>) {
    var updatedThreadIDs: MutableLongSet? = null

    writableDatabase.compileStatement("""
        INSERT INTO ${ThreadDatabase.TABLE_NAME} (${ThreadDatabase.ADDRESS}, ${ThreadDatabase.LAST_SEEN})
        VALUES (?, ?)
        ON CONFLICT (${ThreadDatabase.ADDRESS}) 
            DO UPDATE SET ${ThreadDatabase.LAST_SEEN} = EXCLUDED.${ThreadDatabase.LAST_SEEN}
            WHERE ${ThreadDatabase.LAST_SEEN} != EXCLUDED.${ThreadDatabase.LAST_SEEN}
        RETURNING ${ThreadDatabase.ID}
    """).use { stmt ->
        lastReads.forEach { (address, lastRead) ->
            stmt.clearBindings()
            stmt.bindString(1, address.address)
            stmt.bindLong(2, lastRead.toEpochMilliseconds())

            try {
                val threadId = stmt.simpleQueryForLong()
                if (updatedThreadIDs == null) {
                    updatedThreadIDs = mutableLongSetOf(threadId)
                } else {
                    updatedThreadIDs.add(threadId)
                }
            } catch (_: SQLiteDoneException) {
                // This happens when we don't have an update for the thread
            }
        }
    }

    updatedThreadIDs?.forEach { notifyThreadUpdated(it) }
}
