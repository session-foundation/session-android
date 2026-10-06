package org.thoughtcrime.securesms.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.session.libsession.messaging.messages.UnsupportedMessage.Kind

/**
 * Runs against the real `mms` schema so the placeholder trigger is exercised the way every delete
 * path in [MmsDatabase] reaches it. Synthetic payloads only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 36) // Matches SnodeDatabaseTest: the app relies on a recent SQLite
class UnsupportedMessageDatabaseTest {
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: UnsupportedMessageDatabase

    private val sqlite: SupportSQLiteDatabase get() = helper.writableDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(MmsDatabase.CREATE_TABLE)
                    UnsupportedMessageDatabase.createTable(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = UnsupportedMessageDatabase(helper = { helper })
    }

    private fun insertMmsRow(threadId: Long): Long =
        sqlite.compileStatement("INSERT INTO ${MmsDatabase.TABLE_NAME} (${MmsSmsColumns.THREAD_ID}, ${MmsDatabase.DATE_SENT}) VALUES (?, 1)").use {
            it.bindLong(1, threadId)
            it.executeInsert()
        }

    private fun insert(
        hash: String,
        kind: Kind = Kind.UNKNOWN_TYPE,
        size: Int = 10,
        placeholderId: Long? = null,
        expiresAtMs: Long? = null,
        receivedAtMs: Long = 0L,
        version: String = "1.0.0-1",
    ): Boolean = db.insert(
        kind = kind,
        swarmPublicKey = "05" + "00".repeat(32),
        namespace = 0,
        hash = hash,
        serverTimestampMs = 100L,
        serverExpiryMs = null,
        data = ByteArray(size) { it.toByte() },
        placeholderMessageId = placeholderId,
        expiresAtMs = expiresAtMs,
        receivedAtMs = receivedAtMs,
        lastAttemptVersion = version,
    )

    private fun count(): Long = sqlite.query("SELECT COUNT(*) FROM ${UnsupportedMessageDatabase.TABLE_NAME}")
        .use { it.moveToNext(); it.getLong(0) }

    @Test
    fun `table has exactly the shared column names`() {
        val columns = sqlite.query("PRAGMA table_info(${UnsupportedMessageDatabase.TABLE_NAME})").use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(cursor.getColumnIndexOrThrow("name")) else null }.toList()
        }

        assertEquals(
            listOf(
                "id", "kind", "swarm_public_key", "namespace", "hash", "server_timestamp_ms", "server_expiry_ms",
                "data", "placeholder_message_id", "expires_at_ms", "received_at_ms", "last_attempt_version"
            ),
            columns
        )
    }

    @Test
    fun `round trips a record and ignores a duplicate hash`() {
        assertTrue(insert("hash1", kind = Kind.NEWER_FORMAT, size = 4))
        assertFalse(insert("hash1"))
        assertTrue(db.exists("hash1"))
        assertFalse(db.exists("hash2"))

        val id = db.idsNotAttemptedBy("2.0.0-2").single()
        val record = db.get(id)!!

        assertEquals(Kind.NEWER_FORMAT, record.kind)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), record.data)
        assertNull(record.placeholderMessageId)
        assertNull(record.serverExpiryMs)
    }

    @Test
    fun `only records not attempted by a version are returned for it`() {
        insert("hash1", version = "1.0.0-1")
        insert("hash2", version = "1.0.0-1")

        val ids = db.idsNotAttemptedBy("1.0.0-2")
        assertEquals(2, ids.size)

        db.setLastAttemptVersion(ids.first(), "1.0.0-2")
        assertEquals(listOf(ids.last()), db.idsNotAttemptedBy("1.0.0-2"))
    }

    @Test
    fun `deleting a placeholder deletes its record`() {
        val placeholder = insertMmsRow(threadId = 1)
        val other = insertMmsRow(threadId = 1)
        insert("hash1", placeholderId = placeholder)
        insert("hash2")

        sqlite.execSQL("DELETE FROM ${MmsDatabase.TABLE_NAME} WHERE _id = ?", arrayOf(other))
        assertEquals(2L, count())

        sqlite.execSQL("DELETE FROM ${MmsDatabase.TABLE_NAME} WHERE _id = ?", arrayOf(placeholder))
        assertFalse(db.exists("hash1"))
        assertTrue(db.exists("hash2"))
    }

    @Test
    fun `deleting a conversation deletes its records and no others`() {
        insert("thread1", placeholderId = insertMmsRow(threadId = 1))
        insert("thread1b", placeholderId = insertMmsRow(threadId = 1))
        insert("thread2", placeholderId = insertMmsRow(threadId = 2))

        // The statement MmsDatabase.deleteThreads issues
        sqlite.execSQL("DELETE FROM ${MmsDatabase.TABLE_NAME} WHERE thread_id IN (SELECT value FROM json_each(?))", arrayOf("[1]"))

        assertFalse(db.exists("thread1"))
        assertFalse(db.exists("thread1b"))
        assertTrue(db.exists("thread2"))
    }

    @Test
    fun `marking a placeholder deleted for me deletes its record`() {
        val placeholder = insertMmsRow(threadId = 1)
        insert("hash1", placeholderId = placeholder)

        sqlite.execSQL(UnsupportedMessageDatabase.DELETE_FOR_PLACEHOLDER_SQL, arrayOf(placeholder))

        assertFalse(db.exists("hash1"))
    }

    @Test
    fun `expired records are removed`() {
        insert("expired", expiresAtMs = 1_000L)
        insert("exactly", expiresAtMs = 2_000L)
        insert("later", expiresAtMs = 3_000L)
        insert("never")

        db.enforceLimits(nowMs = 2_000L)

        assertFalse(db.exists("expired"))
        assertFalse(db.exists("exactly"))
        assertTrue(db.exists("later"))
        assertTrue(db.exists("never"))
    }

    @Test
    fun `eviction removes newer format rows first, oldest first, until within budget`() {
        insert("unknownOld", kind = Kind.UNKNOWN_TYPE, size = 10, receivedAtMs = 1)
        insert("newerNew", kind = Kind.NEWER_FORMAT, size = 10, receivedAtMs = 4)
        insert("newerOld", kind = Kind.NEWER_FORMAT, size = 10, receivedAtMs = 2)
        insert("unknownNew", kind = Kind.UNKNOWN_TYPE, size = 10, receivedAtMs = 3)

        db.enforceLimits(nowMs = 0L, maxRetainedDataBytes = 40)
        assertEquals(4L, count())

        db.enforceLimits(nowMs = 0L, maxRetainedDataBytes = 25)
        assertFalse(db.exists("newerOld"))
        assertFalse(db.exists("newerNew"))
        assertTrue(db.exists("unknownOld"))
        assertTrue(db.exists("unknownNew"))

        db.enforceLimits(nowMs = 0L, maxRetainedDataBytes = 15)
        assertFalse(db.exists("unknownOld"))
        assertTrue(db.exists("unknownNew"))
    }
}
