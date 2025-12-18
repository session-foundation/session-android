package org.thoughtcrime.securesms.database

import android.content.Context
import androidx.collection.LongList
import androidx.collection.LongSet
import androidx.collection.MutableLongList
import androidx.sqlite.db.transaction
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import javax.inject.Provider

abstract class MessagingDatabase(
    context: Context,
    databaseHelper: Provider<SQLCipherOpenHelper>,
    private val tableName: String,
) : Database(context, databaseHelper) {

    abstract fun markExpireStarted(messageId: Long, startTime: Long)

    abstract fun markAsSent(messageId: Long, sent: Boolean)

    abstract fun markAsSyncing(id: Long)

    abstract fun markAsResyncing(id: Long)

    abstract fun markAsSyncFailed(id: Long)


    abstract fun markAsDeleted(messageId: Long, isOutgoing: Boolean, displayedMessage: String)

    abstract fun getExpiredMessageIDs(nowMills: Long): List<Long>

    abstract fun getNextExpiringTimestamp(): Long

    abstract fun deleteMessage(messageId: Long)
    abstract fun deleteMessages(messageIds: Collection<Long>)

    abstract fun updateThreadId(fromId: Long, toId: Long)

    fun markAllNotified() {
        writableDatabase.execSQL("""
            UPDATE $tableName
            SET ${MmsSmsColumns.NOTIFIED} = 1
            WHERE ${MmsSmsColumns.NOTIFIED} = 0
        """)
    }

    /**
     * Returns the set of message IDs from [messageIDs] that do not exist or are marked as deleted.
     */
    fun findNonExistentOrDeletedMessages(messageIDs: LongList): LongList {
        return readableDatabase.transaction(exclusive = false) {
            //language=roomsql
            execSQL("CREATE TABLE temp_message_ids (id INTEGER PRIMARY KEY)")
            //language=roomsql
            compileStatement("INSERT OR IGNORE INTO temp_message_ids (id) VALUES (?)").use { insertStmt ->
                messageIDs.forEach { id ->
                    insertStmt.bindLong(1, id)
                    insertStmt.executeInsert()
                    insertStmt.clearBindings()
                }
            }

            //language=roomsql
            val result = query("""
                SELECT id
                FROM temp_message_ids t
                WHERE id NOT IN (
                    SELECT id
                    FROM $tableName
                    WHERE NOT ${MmsSmsColumns.IS_DELETED}
                )
            """).use { cursor ->
                MutableLongList(cursor.count).apply {
                    while (cursor.moveToNext()) {
                        add(cursor.getLong(0))
                    }
                }
            }

            //language=roomsql
            execSQL("DROP TABLE temp_message_ids")

            result
        }
    }


    class SyncMessageId(val address: Address, val timestamp: Long)

    class InsertResult(val messageId: Long, val threadId: Long)
}
