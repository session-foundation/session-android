package org.thoughtcrime.securesms.database

import androidx.core.database.getStringOrNull
import androidx.sqlite.db.transaction
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.withUserConfigs
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.util.asSequence
import org.thoughtcrime.securesms.util.get

fun ThreadDatabase.queryThreads(addresses: Collection<Address.Conversable>): List<ThreadRecord> {
    val convoInfo = configFactory.get().withUserConfigs { configs ->
        addresses.associateWith { address ->
            configs.convoInfoVolatile.get(address)
        }
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
            convoInfo.forEach { (address, convo) ->
                stmt.clearBindings()
                stmt.bindString(1, address.address)
                stmt.bindLong(2, convo?.lastRead ?: 0L)
                stmt.bindLong(3, if (convo?.unread == true) 1 else 0)
                stmt.executeInsert()
            }
        }

        //language=roomsql
        val result = query(
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
                    AND NOT s.${SmsDatabase.READ}
                    AND NOT s.${MmsSmsColumns.IS_DELETED}
            ) AS smsUnreadCount,
            
            -- Count unread sms with mention
            (
                SELECT COUNT(*) 
                FROM ${SmsDatabase.TABLE_NAME} s 
                WHERE s.${SmsDatabase.THREAD_ID} = threads.${ThreadDatabase.ID} 
                    AND ${SmsDatabase.DATE_SENT} > input.last_read 
                    AND NOT s.${SmsDatabase.READ}
                    AND s.${SmsDatabase.HAS_MENTION}
                    AND NOT s.${MmsSmsColumns.IS_DELETED}
            ) AS smsUnreadMentionCount,
            
            -- Count unread mms
            (
                SELECT COUNT(*) 
                FROM ${MmsDatabase.TABLE_NAME} m 
                WHERE m.${MmsSmsColumns.THREAD_ID} = threads.${ThreadDatabase.ID} 
                    AND ${MmsDatabase.DATE_SENT} > input.last_read 
                    AND NOT m.${MmsSmsColumns.READ}
                    AND NOT m.${MmsSmsColumns.IS_DELETED}
            ) AS mmsUnreadCount,
            
            -- Count unread mms with mention
            (
                SELECT COUNT(*) 
                FROM ${MmsDatabase.TABLE_NAME} m 
                WHERE m.${MmsSmsColumns.THREAD_ID} = threads.${ThreadDatabase.ID} 
                    AND ${MmsDatabase.DATE_SENT} > input.last_read 
                    AND NOT m.${MmsSmsColumns.READ}
                    AND m.${MmsSmsColumns.HAS_MENTION}
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
    """
        ).use { cursor ->
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

        // Clean up our temp table
        //language=roomsql
        execSQL("DROP TABLE threads_query_input")

        result
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
    """).use { it.count > 0 }

    if (hasOutgoingSms) return true

    return readableDatabase.rawQuery("""
        SELECT 1 FROM ${MmsDatabase.TABLE_NAME}
        WHERE ${MmsSmsColumns.THREAD_ID} = ?
          AND ${MmsSmsColumns.IS_OUTGOING}
          AND NOT ${MmsSmsColumns.IS_DELETED}
        LIMIT 1
    """).use { it.count > 0 }
}
