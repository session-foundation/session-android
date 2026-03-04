package org.thoughtcrime.securesms.database

import android.database.Cursor
import androidx.collection.LongLongMap
import androidx.collection.MutableLongLongMap
import androidx.core.database.getStringOrNull
import androidx.sqlite.db.transaction
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.Address.Companion.toConversableAddress
import org.session.libsession.utilities.recipients.RecipientData
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.util.asSequence
import kotlin.time.Instant

fun ThreadDatabase.getThreads(addresses: Collection<Address.Conversable>): List<ThreadRecord> {
    if (addresses.isEmpty()) return emptyList()

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

fun ThreadDatabase.getAddressAndLastSeen(id: Long): Pair<Address.Conversable, Long>? {
    return readableDatabase.query(
        "SELECT ${ThreadDatabase.ADDRESS}, ${ThreadDatabase.LAST_SEEN} FROM ${ThreadDatabase.TABLE_NAME} WHERE ${ThreadDatabase.ID} = ?",
        arrayOf(id)
    ).use {
        if (it.moveToNext()) {
            (it.getString(0).toAddress() as Address.Conversable) to it.getLong(1)
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

fun ThreadDatabase.deleteThread(id: Long) {
    //language=roomsql
    writableDatabase.query("""
        DELETE FROM ${ThreadDatabase.TABLE_NAME} WHERE ${ThreadDatabase.ID} = ?
        RETURNING ${ThreadDatabase.ADDRESS}
    """, arrayOf(id)).use { cursor ->
        if (cursor.moveToNext()) {
            notifyThreadUpdated(id, cursor.getString(0).toConversableAddress())
        }
    }
}

typealias ThreadId = Long

class EnsureThreadsResult(
    val deleted: List<Pair<ThreadId, Address.Conversable>>,
    val created: List<Pair<ThreadId, Address.Conversable>>,
)

private fun Cursor.readIdAddressList(): List<Pair<ThreadId, Address.Conversable>> {
    return buildList(count) {
        while (moveToNext()) {
            add(getLong(0) to getString(1).toConversableAddress())
        }
    }
}

private fun ThreadDatabase.notifyUpdated(changes: List<Pair<ThreadId, Address.Conversable>>) {
    changes.forEach { (id, address) ->
        notifyThreadUpdated(id, address)
    }
}


/**
 * This method ensures that the threads for the given addresses exist in the database, AND
 * deletes any threads that are not in the given addresses.
 */
fun ThreadDatabase.ensureThreads(addresses: Iterable<Address.Conversable>): EnsureThreadsResult {
    return writableDatabase.transaction {
        // First store the addresses in a temp table for later use
        //language=roomsql
        execSQL("CREATE TEMP TABLE tmp_addresses (address TEXT NOT NULL PRIMARY KEY)")
        //language=roomsql
        compileStatement("INSERT INTO tmp_addresses (address) VALUES (?)").use { stmt ->
            addresses.forEach {
                stmt.bindString(1, it.address)
                stmt.execute()
            }
        }

        // Delete threads that are not in the tmp_addresses
        //language=roomsql
        val deleted = query("""
            DELETE FROM ${ThreadDatabase.TABLE_NAME} 
            WHERE ${ThreadDatabase.ADDRESS} NOT IN (SELECT address FROM tmp_addresses)
            RETURNING ${ThreadDatabase.ID}, ${ThreadDatabase.ADDRESS}
        """).use(Cursor::readIdAddressList)

        // Create threads
        //language=roomsql
        val created = query("""
            INSERT OR IGNORE INTO ${ThreadDatabase.TABLE_NAME} (${ThreadDatabase.ADDRESS})
            SELECT address FROM tmp_addresses
            RETURNING ${ThreadDatabase.ID}, ${ThreadDatabase.ADDRESS}
        """).use(Cursor::readIdAddressList)

        //language=roomsql
        execSQL("DROP TABLE tmp_addresses")

        EnsureThreadsResult(deleted = deleted, created = created)
    }.also { result ->
        notifyUpdated(result.deleted)
        notifyUpdated(result.created)
    }
}

/**
 * Update or create a thread record to store the given lastRead timestamp.
 */
fun ThreadDatabase.upsertThreadLastSeen(lastReads: Collection<Pair<Address.Conversable, Instant>>) {
    if (lastReads.isEmpty()) return

    val changes = writableDatabase.transaction {
        //language=roomsql
        execSQL("CREATE TEMP TABLE tmp_last_reads (address TEXT NOT NULL PRIMARY KEY, last_read INTEGER NOT NULL)")
        //language=roomsql
        compileStatement("INSERT INTO tmp_last_reads (address, last_read) VALUES (?, ?)").use { stmt ->
            lastReads.forEach { (address, lastRead) ->
                stmt.bindString(1, address.address)
                stmt.bindLong(2, lastRead.toEpochMilliseconds())
                stmt.execute()
            }
        }

        //language=roomsql
        val r = query("""
            INSERT INTO ${ThreadDatabase.TABLE_NAME} (${ThreadDatabase.ADDRESS}, ${ThreadDatabase.LAST_SEEN})
            SELECT address, last_read FROM tmp_last_reads WHERE true
            ON CONFLICT (${ThreadDatabase.ADDRESS}) 
                DO UPDATE SET ${ThreadDatabase.LAST_SEEN} = EXCLUDED.${ThreadDatabase.LAST_SEEN}
                WHERE ${ThreadDatabase.LAST_SEEN} != EXCLUDED.${ThreadDatabase.LAST_SEEN}
            RETURNING ${ThreadDatabase.ID}, ${ThreadDatabase.ADDRESS}
        """).use(Cursor::readIdAddressList)

        //language=roomsql
        execSQL("DROP TABLE tmp_last_reads")

        r
    }

    notifyUpdated(changes)
}

fun ThreadDatabase.getOrCreateThreadIdFor(address: Address.Conversable): ThreadId {
    // Fast path without exclusive write lock:
    getThreadId(address)?.let { return it }

    // Slow path with exclusive lock:
    //language=roomsql
    writableDatabase.query("""
        INSERT INTO ${ThreadDatabase.TABLE_NAME} (${ThreadDatabase.ADDRESS})
        VALUES (?)
        ON CONFLICT DO NOTHING
        RETURNING ${ThreadDatabase.ID}
    """, arrayOf(address.address)).use { cursor ->
        require(cursor.moveToNext()) { "Unable to insert a new thread" }
        val threadId = cursor.getLong(0)
        notifyThreadUpdated(threadId, address)
        return threadId
    }
}

fun ThreadDatabase.getThreadId(address: Address.Conversable): ThreadId? {
    //language=roomsql
    readableDatabase.query("""
        SELECT ${ThreadDatabase.ID} 
        FROM ${ThreadDatabase.TABLE_NAME} 
        WHERE ${ThreadDatabase.ADDRESS} = ?""",
        arrayOf(address.address)).use { cursor ->
        if (cursor.moveToNext()) {
            return cursor.getLong(0)
        }
    }

    return null
}

fun ThreadDatabase.getRecipientAddress(threadId: Long): Address.Conversable? {
    //language=roomsql
    readableDatabase.query("""
        SELECT ${ThreadDatabase.ADDRESS} 
        FROM ${ThreadDatabase.TABLE_NAME} 
        WHERE ${ThreadDatabase.ID} = ?""",
        arrayOf(threadId)).use { cursor ->
        if (cursor.moveToNext()) {
            return cursor.getString(0).toConversableAddress()
        }
    }

    return null
}

fun ThreadDatabase.getThreadIDs(addresses: Collection<Address.Conversable>): List<Pair<ThreadId, Address.Conversable>> {
    if (addresses.isEmpty()) return emptyList()

    val addressesAsJson = json.encodeToString(addresses)

    //language=roomsql
    return readableDatabase.query("""
        SELECT ${ThreadDatabase.ID}, ${ThreadDatabase.ADDRESS} 
        FROM ${ThreadDatabase.TABLE_NAME} 
        WHERE ${ThreadDatabase.ADDRESS} IN (SELECT value FROM json_each(?))""",
        arrayOf(addressesAsJson)).use(Cursor::readIdAddressList)
}