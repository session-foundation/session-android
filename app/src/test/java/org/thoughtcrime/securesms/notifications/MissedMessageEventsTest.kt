package org.thoughtcrime.securesms.notifications

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase.CONFLICT_NONE
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsColumns
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.model.MessageChanges
import org.thoughtcrime.securesms.database.model.MessageChanges.ChangeType
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.preferences.PreferenceKey
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 36) // The sms and mms tables use generated columns, which need a recent sqlite
class MissedMessageEventsTest {
    private val openHelper = createInMemoryMessageTables()
    private val db get() = openHelper.writableDatabase
    private val prefs = InMemoryPreferences()
    private val events = MissedMessageEvents(prefs, Provider { openHelper })

    @Test
    fun `a message stored before subscribing is replayed as an Added event for its thread`() = runTest {
        startAfterAnEarlierRun()
        val sms = insertSms(threadId = 7)
        val mms = insertMms(threadId = 7)
        val otherThread = insertSms(threadId = 8)

        assertEquals(
            setOf(
                MessageChanges(ChangeType.Added, listOf(sms, mms), 7),
                MessageChanges(ChangeType.Added, listOf(otherThread), 8),
            ),
            replayed().toSet()
        )
    }

    @Test
    fun `messages a handler has seen are not replayed`() = runTest {
        startAfterAnEarlierRun()
        val seen = insertSms(threadId = 7)
        val seenMms = insertMms(threadId = 7)
        val missed = insertSms(threadId = 7)
        events.markSeen(MessageChanges(ChangeType.Added, listOf(seen, seenMms), 7))
        // Only Added events count.
        events.markSeen(MessageChanges(ChangeType.Updated, missed, 7))

        assertEquals(listOf(MessageChanges(ChangeType.Added, listOf(missed), 7)), replayed())
    }

    @Test
    fun `a new message that reuses a deleted message's sms id is replayed`() = runTest {
        startAfterAnEarlierRun()
        val first = insertSms(threadId = 7)
        events.markSeen(MessageChanges(ChangeType.Added, first, 7))
        db.delete(SmsDatabase.TABLE_NAME, "${MmsSmsColumns.ID} = ?", arrayOf(first.id))

        val reused = insertSms(threadId = 7)

        assertEquals(first, reused)
        assertEquals(listOf(MessageChanges(ChangeType.Added, listOf(reused), 7)), replayed())
    }

    @Test
    fun `outgoing, deleted and already-read messages are not replayed`() = runTest {
        startAfterAnEarlierRun()
        insertSms(threadId = 7, type = SENT_TYPE, read = 1)
        insertMms(threadId = 7, type = SENT_TYPE, read = 1)
        insertSms(threadId = 7, type = DELETED_INCOMING_TYPE)
        insertSms(threadId = 7, read = 1)

        assertEquals(emptyList<MessageChanges>(), replayed())
    }

    @Test
    fun `the first run marks the stored messages without replaying them`() = runTest {
        insertSms(threadId = 7)
        insertMms(threadId = 7)

        assertEquals(emptyList<MessageChanges>(), replayed())

        val next = insertSms(threadId = 7)
        assertEquals(listOf(MessageChanges(ChangeType.Added, listOf(next), 7)), replayed())
    }

    @Test
    fun `a message stored while the database is read still arrives live`() = runTest {
        startAfterAnEarlierRun()
        // The change flows keep no replay, so this only arrives if the subscription came first.
        val live = MutableSharedFlow<MessageChanges>(extraBufferCapacity = 24)
        val storedDuringRead = MessageChanges(ChangeType.Added, MessageId(11, false), 7)
        val events = MissedMessageEvents(prefs, Provider {
            check(live.subscriptionCount.value == 1)
            live.tryEmit(storedDuringRead)
            openHelper
        })

        assertEquals(storedDuringRead, events.withMissedMessages(listOf(live), toEvent = { it }).first())
    }

    /** The Added events one subscription replays. With no live flows, the flow ends after them. */
    private suspend fun replayed(): List<MessageChanges> =
        events.withMissedMessages(emptyList<Flow<MessageChanges>>(), toEvent = { it }).toList()

    private fun startAfterAnEarlierRun() {
        prefs[PreferenceKey.boolean("notification.missed_message_tracking_started")] = true
    }

    private fun insertSms(threadId: Long, type: Long = INBOX_TYPE, read: Int = 0): MessageId {
        val id = db.insert(SmsDatabase.TABLE_NAME, CONFLICT_NONE, ContentValues().apply {
            put(MmsSmsColumns.THREAD_ID, threadId)
            put(SmsDatabase.TYPE, type)
            put(MmsSmsColumns.READ, read)
        })
        return MessageId(id, mms = false)
    }

    private fun insertMms(threadId: Long, type: Long = INBOX_TYPE, read: Int = 0): MessageId {
        val id = db.insert(MmsDatabase.TABLE_NAME, CONFLICT_NONE, ContentValues().apply {
            put(MmsSmsColumns.THREAD_ID, threadId)
            put(MmsDatabase.MESSAGE_BOX, type)
            put(MmsSmsColumns.READ, read)
        })
        return MessageId(id, mms = true)
    }

    private class InMemoryPreferences : PreferenceStorage {
        private val values = mutableMapOf<String, Any?>()

        override fun <T> set(key: PreferenceKey<T>, value: T) {
            values[key.name] = value
        }

        override fun remove(key: PreferenceKey<*>) {
            values.remove(key.name)
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> get(key: PreferenceKey<T>): T {
            if (key.name in values) return values[key.name] as T
            return (key.strategy as PreferenceKey.Strategy.PrimitiveBoolean).defaultValue as T
        }

        override fun changes(): Flow<PreferenceKey<*>> = emptyFlow()

        override fun <T> watch(scope: CoroutineScope, key: PreferenceKey<T>): StateFlow<T> =
            throw UnsupportedOperationException()
    }

    companion object {
        // MmsSmsColumns.Types' base types, which are package-private.
        private const val DELETED_INCOMING_TYPE = 19L
        private const val INBOX_TYPE = 20L
        private const val SENT_TYPE = 23L

        fun createInMemoryMessageTables(): SupportSQLiteOpenHelper {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val config = SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(SmsDatabase.CREATE_TABLE)
                        SmsDatabase.addOutgoingColumn(db)
                        db.execSQL(MmsDatabase.CREATE_TABLE)
                        MmsDatabase.addOutgoingColumn(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()

            return FrameworkSQLiteOpenHelperFactory().create(config)
        }
    }
}
