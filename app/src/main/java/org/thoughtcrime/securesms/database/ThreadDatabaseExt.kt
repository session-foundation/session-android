package org.thoughtcrime.securesms.database

import androidx.collection.SimpleArrayMap
import androidx.core.database.getStringOrNull
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.transaction
import network.loki.messenger.libsession_util.util.Conversation
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.withUserConfigs
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.util.asSequence
import org.thoughtcrime.securesms.util.get
import kotlin.time.Instant

fun ThreadDatabase.queryThreads(addresses: Collection<Address.Conversable>): List<ThreadRecord> {
    val convoInfo = configFactory.get().withUserConfigs { configs ->
        val out = SimpleArrayMap<Address.Conversable, Conversation>()

        for (address in addresses) {
            val convo = configs.convoInfoVolatile.get(address)
            if (convo != null) {
                out.put(address, convo)
            }
        }

        out
    }

    // For our query, we need to fill in some information first before the main query can be done.
    // The information we are filling in is:
    // 1. The address of the thread
    // 2. The "lastRead" timestamp from the volatile config
    // 3. The "unread" status from the volatile config
    return readableDatabase.transaction(exclusive = false) {
        //language=roomsql
        execSQL(
            """
            CREATE TEMP TABLE IF NOT EXISTS threads_query_input(
                address TEXT PRIMARY KEY,
                last_read INTEGER NOT NULL,
                unread BOOLEAN NOT NULL
            )
        """
        )

        //language=roomsql
        execSQL("DELETE FROM threads_query_input WHERE 1")

        //language=roomsql
        compileStatement(
            """
            INSERT OR REPLACE INTO threads_query_input (address, last_read, unread)
            VALUES (?, ?, ?)
        """
        ).use { stmt ->
            addresses.forEach { address ->
                val convo = convoInfo[address]
                stmt.clearBindings()
                stmt.bindString(1, address.address)
                stmt.bindLong(2, convo?.lastRead ?: 0L)
                stmt.bindLong(3, if (convo?.unread == true) 1 else 0)
                stmt.executeInsert()
            }
        }

        //language=roomsql
        val cursor = query(
            """
            SELECT 
            threads.${ThreadDatabase.ID},
            input.address,
            
            -- Query the groupInviteTable to find out who invited the user to this group
            (SELECT ${LokiMessageDatabase.invitingSessionId} FROM ${LokiMessageDatabase.groupInviteTable} WHERE ${LokiMessageDatabase.threadID} = threads.${ThreadDatabase.ID} LIMIT 1) AS invitingAdminId,
            
            -- Return unread as is
            input.unread,
            
            -- Count unread sms
            (
                SELECT COUNT(*) 
                FROM ${SmsDatabase.TABLE_NAME} s 
                WHERE s.${SmsDatabase.THREAD_ID} = threads.${ThreadDatabase.ID} 
                    AND ${SmsDatabase.DATE_SENT} > input.last_read 
                    AND NOT s.${MmsSmsColumns.IS_OUTGOING}
                    AND NOT s.${MmsSmsColumns.IS_DELETED}
            ) AS smsUnreadCount,
            
            -- Count unread sms with mention
            (
                SELECT COUNT(*) 
                FROM ${SmsDatabase.TABLE_NAME} s 
                WHERE s.${SmsDatabase.THREAD_ID} = threads.${ThreadDatabase.ID} 
                    AND ${SmsDatabase.DATE_SENT} > input.last_read 
                    AND s.${SmsDatabase.HAS_MENTION}
                    AND NOT s.${MmsSmsColumns.IS_OUTGOING}
                    AND NOT s.${MmsSmsColumns.IS_DELETED}
            ) AS smsUnreadMentionCount,
            
            -- Count unread mms
            (
                SELECT COUNT(*) 
                FROM ${MmsDatabase.TABLE_NAME} m 
                WHERE m.${MmsSmsColumns.THREAD_ID} = threads.${ThreadDatabase.ID} 
                    AND ${MmsDatabase.DATE_SENT} > input.last_read 
                    AND NOT m.${MmsSmsColumns.IS_OUTGOING}
                    AND NOT m.${MmsSmsColumns.IS_DELETED}
            ) AS mmsUnreadCount,
            
            -- Count unread mms with mention
            (
                SELECT COUNT(*) 
                FROM ${MmsDatabase.TABLE_NAME} m 
                WHERE m.${MmsSmsColumns.THREAD_ID} = threads.${ThreadDatabase.ID} 
                    AND ${MmsDatabase.DATE_SENT} > input.last_read 
                    AND m.${MmsSmsColumns.HAS_MENTION}
                    AND NOT m.${MmsSmsColumns.IS_OUTGOING}
                    AND NOT m.${MmsSmsColumns.IS_DELETED}
            ) AS mmsUnreadMentionCount,
            
             -- Count sms
            (
                SELECT COUNT(*) 
                FROM ${SmsDatabase.TABLE_NAME} s 
                WHERE s.${SmsDatabase.THREAD_ID} = threads.${ThreadDatabase.ID} 
            ),
            
            -- Count mms
            (
                SELECT COUNT(*) 
                FROM ${MmsDatabase.TABLE_NAME} m 
                WHERE m.${MmsSmsColumns.THREAD_ID} = threads.${ThreadDatabase.ID} 
            )
        FROM ${ThreadDatabase.TABLE_NAME} AS threads
        INNER JOIN threads_query_input AS input ON threads.${ThreadDatabase.ADDRESS} = input.address
    """)

        // Trigger the query to run within this transaction, this is important as we need to query the
        // cursor outside of the transaction later.
        cursor.count

        // Clean up our temp table
        //language=roomsql
        execSQL("DROP TABLE threads_query_input")

        // Note transforming the cursor into a list MUST be done outside of the transaction,
        // as part of the transformation may involve further DB queries and may involve acquiring
        // locks that can deadlock with the current transaction.
        cursor
    }.use { cursor ->
        cursor.asSequence()
            .map { cursor ->
                val threadId = cursor.getLong(0)
                val threadAddress = cursor.getString(1).toAddress() as Address.Conversable
                val invitingAdminId = cursor.getStringOrNull(2)
                val unread = cursor.getLong(3) != 0L
                val smsUnreadCount = cursor.getLong(4)
                val smsUnreadMentionCount = cursor.getLong(5)
                val mmsUnreadCount = cursor.getLong(6)
                val mmsUnreadMentionCount = cursor.getLong(7)
                val smsCount = cursor.getLong(8)
                val mmsCount = cursor.getLong(9)

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
                    isUnread = unread,
                    date = date,
                    invitingAdminId = invitingAdminId
                )
            }
            .toList()
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

fun ThreadDatabase.updateThreadLastReads(lastRead: Sequence<Pair<Address.Conversable, Instant>>) {
    writableDatabase.compileStatement("""
        INSERT INTO ${ThreadDatabase.TABLE_NAME} (${ThreadDatabase.ADDRESS}, ${ThreadDatabase.LAST_SEEN})
        VALUES (?, ?)
        ON CONFLICT (${ThreadDatabase.ADDRESS}) DO UPDATE ${ThreadDatabase.LAST_SEEN} = EXCLUDED.${ThreadDatabase.LAST_SEEN}
    """)
    TODO()
}
