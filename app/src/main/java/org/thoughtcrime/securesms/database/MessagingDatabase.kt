package org.thoughtcrime.securesms.database

import android.content.Context
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import javax.inject.Provider

abstract class MessagingDatabase(
    context: Context,
    databaseHelper: Provider<SQLCipherOpenHelper>
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


    class SyncMessageId(val address: Address, val timestamp: Long)

    class InsertResult(val messageId: Long, val threadId: Long)
}
