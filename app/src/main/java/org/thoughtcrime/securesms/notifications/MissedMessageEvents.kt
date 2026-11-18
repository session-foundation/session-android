package org.thoughtcrime.securesms.notifications

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsColumns
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.model.MessageChanges
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.preferences.PreferenceKey
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Replays the incoming messages that no notification handler has seen.
 *
 * The handlers learn about new messages from the databases' change flows, which keep no replay. A
 * push or background poll that cold-starts the process stores its message before
 * [NotificationProcessor] subscribes, so the message's Added event is dropped and it never notifies.
 * Each message a handler deals with is marked in the `notified` column, and each subscription starts
 * by replaying the unread incoming messages still unmarked. Messages a handler already saw aren't
 * replayed, so an unread message the user dismissed doesn't notify again on every cold start.
 *
 * The mark is per row rather than a high-water id because sms has no AUTOINCREMENT: deleting its
 * newest rows frees their ids for the next inserts.
 */
@Singleton
class MissedMessageEvents @Inject constructor(
    private val prefs: PreferenceStorage,
    private val openHelper: Provider<SupportSQLiteOpenHelper>,
) {
    /**
     * The events from [live], plus one Added event for each thread with messages no handler has
     * seen. [live] is subscribed before the database is read, so a message stored in between
     * arrives at least once; the handlers already ignore a message they've notified.
     */
    fun <T> withMissedMessages(live: List<Flow<T>>, toEvent: (MessageChanges) -> T): Flow<T> = channelFlow {
        for (flow in live) {
            // Undispatched, so the change flow has this subscriber before the read below.
            launch(start = CoroutineStart.UNDISPATCHED) {
                flow.collect { send(it) }
            }
        }

        for (change in findMissed()) {
            send(toEvent(change))
        }
    }

    /**
     * Records that a handler has dealt with [change], so it isn't replayed. Call it once the
     * handler has finished with the event: a process that dies before then replays it next time.
     */
    fun markSeen(change: MessageChanges) {
        if (change.changeType != MessageChanges.ChangeType.Added) return

        val db = openHelper.get().writableDatabase
        for ((isMms, ids) in change.ids.groupBy { it.mms }) {
            db.execSQL("""
                UPDATE ${tableName(isMms)} SET ${MmsSmsColumns.NOTIFIED} = 1
                WHERE ${MmsSmsColumns.ID} IN (${ids.joinToString(",") { it.id.toString() }})
                    AND ${MmsSmsColumns.NOTIFIED} = 0
            """)
        }
    }

    private fun findMissed(): List<MessageChanges> {
        val db = openHelper.get().writableDatabase

        if (!prefs[TRACKING_STARTED]) {
            // Nothing has been marked yet, e.g. the first run of this version. Mark what's stored
            // now rather than notifying every unread message.
            db.markAllUnreadNotified()
            prefs[TRACKING_STARTED] = true
            return emptyList()
        }

        val missed = db.getUnnotifiedIncomingMessages()
        if (missed.isNotEmpty()) {
            Log.d(TAG, "Replaying ${missed.size} message(s) stored while no handler was listening")
        }

        return missed.groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .map { (threadId, ids) -> MessageChanges(MessageChanges.ChangeType.Added, ids, threadId) }
    }

    private fun SupportSQLiteDatabase.markAllUnreadNotified() {
        for (isMms in listOf(false, true)) {
            execSQL("""
                UPDATE ${tableName(isMms)} SET ${MmsSmsColumns.NOTIFIED} = 1
                WHERE ${MmsSmsColumns.READ} = 0 AND ${MmsSmsColumns.NOTIFIED} = 0
            """)
        }
    }

    /**
     * Incoming messages that are unread and unmarked, with their threads. A message read on another
     * device needs no notification. The read and notified tests match the existing
     * (read, notified, thread_id) index on both tables.
     */
    private fun SupportSQLiteDatabase.getUnnotifiedIncomingMessages(): List<Pair<Long, MessageId>> {
        val select = { isMms: Boolean ->
            """
            SELECT ${MmsSmsColumns.THREAD_ID}, ${MmsSmsColumns.ID}, ${if (isMms) 1 else 0}
            FROM ${tableName(isMms)}
            WHERE ${MmsSmsColumns.READ} = 0 AND ${MmsSmsColumns.NOTIFIED} = 0
                AND NOT ${MmsSmsColumns.IS_OUTGOING} AND NOT ${MmsSmsColumns.IS_DELETED}
            """
        }

        return query("${select(false)} UNION ALL ${select(true)}").use { cursor ->
            val result = ArrayList<Pair<Long, MessageId>>(cursor.count)
            while (cursor.moveToNext()) {
                result += cursor.getLong(0) to MessageId(cursor.getLong(1), mms = cursor.getInt(2) == 1)
            }
            result
        }
    }

    private fun tableName(isMms: Boolean): String =
        if (isMms) MmsDatabase.TABLE_NAME else SmsDatabase.TABLE_NAME

    companion object {
        private const val TAG = "MissedMessageEvents"

        private val TRACKING_STARTED = PreferenceKey.boolean("notification.missed_message_tracking_started")
    }
}
