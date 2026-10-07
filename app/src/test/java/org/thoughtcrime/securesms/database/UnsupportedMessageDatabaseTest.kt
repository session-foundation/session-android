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
        sender: String? = null,
        sentTimestampMs: Long? = null,
        namespace: Int = 0,
        data: ByteArray = ByteArray(size) { it.toByte() },
    ): Boolean = db.insert(
        kind = kind,
        swarmPublicKey = "05" + "00".repeat(32),
        namespace = namespace,
        hash = hash,
        sender = sender,
        sentTimestampMs = sentTimestampMs,
        serverTimestampMs = 100L,
        serverExpiryMs = null,
        data = data,
        placeholderMessageId = placeholderId,
        expiresAtMs = expiresAtMs,
        receivedAtMs = receivedAtMs,
        lastAttemptVersion = version,
    )

    private fun idOf(hash: String): Long =
        sqlite.query("SELECT id FROM ${UnsupportedMessageDatabase.TABLE_NAME} WHERE hash = ?", arrayOf(hash))
            .use { it.moveToNext(); it.getLong(0) }

    private fun lastAttemptVersion(hash: String): String =
        sqlite.query("SELECT last_attempt_version FROM ${UnsupportedMessageDatabase.TABLE_NAME} WHERE hash = ?", arrayOf(hash))
            .use { it.moveToNext(); it.getString(0) }

    /** total_bytes and newer_format_count from the stats row, then the same recomputed from the table */
    private fun assertStatsExact() {
        val stats = sqlite.query("SELECT total_bytes, newer_format_count FROM ${UnsupportedMessageDatabase.STATS_TABLE_NAME}").use {
            assertTrue(it.moveToNext())
            val result = it.getLong(0) to it.getLong(1)
            assertFalse("exactly one stats row", it.moveToNext())
            result
        }
        val actual = sqlite.query(
            "SELECT IFNULL(SUM(length(data) + ?), 0), IFNULL(SUM(kind = 'newerFormat'), 0) FROM ${UnsupportedMessageDatabase.TABLE_NAME}",
            arrayOf(UnsupportedMessageDatabase.ROW_OVERHEAD_BYTES)
        ).use { it.moveToNext(); it.getLong(0) to it.getLong(1) }

        assertEquals(actual, stats)
    }

    private fun stats(): Pair<Long, Long> =
        sqlite.query("SELECT total_bytes, newer_format_count FROM ${UnsupportedMessageDatabase.STATS_TABLE_NAME}")
            .use { it.moveToNext(); it.getLong(0) to it.getLong(1) }

    private fun count(): Long = sqlite.query("SELECT COUNT(*) FROM ${UnsupportedMessageDatabase.TABLE_NAME}")
        .use { it.moveToNext(); it.getLong(0) }

    @Test
    fun `table has exactly the shared column names`() {
        val columns = sqlite.query("PRAGMA table_info(${UnsupportedMessageDatabase.TABLE_NAME})").use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(cursor.getColumnIndexOrThrow("name")) else null }.toList()
        }

        assertEquals(
            listOf(
                "id", "kind", "swarm_public_key", "namespace", "hash", "sender", "sent_timestamp_ms",
                "server_timestamp_ms", "server_expiry_ms", "data", "placeholder_message_id", "expires_at_ms",
                "received_at_ms", "last_attempt_version"
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

        val id = idOf("hash1")
        val record = db.get(id)!!

        assertEquals(Kind.NEWER_FORMAT, record.kind)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), record.data)
        assertNull(record.placeholderMessageId)
        assertNull(record.serverExpiryMs)
        assertNull(record.sender)
        assertNull(record.sentTimestampMs)
    }

    @Test
    fun `round trips the sender and sent timestamp`() {
        insert("hash1", sender = SENDER_A, sentTimestampMs = 1_234L)

        val record = db.get(idOf("hash1"))!!

        assertEquals(SENDER_A, record.sender)
        assertEquals(1_234L, record.sentTimestampMs)
    }

    @Test
    fun `an unsend deletes only the record with the same sender and sent timestamp`() {
        insert("target", sender = SENDER_A, sentTimestampMs = 1_000L)
        insert("otherTime", sender = SENDER_A, sentTimestampMs = 2_000L)
        insert("otherSender", sender = SENDER_B, sentTimestampMs = 1_000L)
        insert("newerFormat", kind = Kind.NEWER_FORMAT)

        db.deleteForUnsend(sender = SENDER_A, sentTimestampMs = 1_000L)

        assertFalse(db.exists("target"))
        assertTrue(db.exists("otherTime"))
        assertTrue(db.exists("otherSender"))
        assertTrue(db.exists("newerFormat"))
    }

    @Test
    fun `a detached record survives its placeholder being deleted`() {
        val placeholder = insertMmsRow(threadId = 1)
        insert("hash1", placeholderId = placeholder)
        val id = idOf("hash1")

        db.detachPlaceholder(id, expiresAtMs = 5_000L)
        sqlite.execSQL("DELETE FROM ${MmsDatabase.TABLE_NAME} WHERE _id = ?", arrayOf(placeholder))

        val record = db.get(id)!!
        assertNull(record.placeholderMessageId)
        assertEquals(5_000L, record.expiresAtMs)
    }

    @Test
    fun `stamping newer format rows marks them attempted without returning them`() {
        insert("newer1", kind = Kind.NEWER_FORMAT, version = "1.0.0-1")
        insert("newer2", kind = Kind.NEWER_FORMAT, version = "1.0.0-2")
        insert("unknown", kind = Kind.UNKNOWN_TYPE, version = "1.0.0-1")

        assertEquals(1, db.stampNewerFormatAttempted("1.0.0-2"))

        assertEquals("1.0.0-2", lastAttemptVersion("newer1"))
        assertEquals("1.0.0-2", lastAttemptVersion("newer2"))
        assertEquals("1.0.0-1", lastAttemptVersion("unknown"))
    }

    @Test
    fun `the replay page returns only unknown type rows not attempted by the version`() {
        insert("newer", kind = Kind.NEWER_FORMAT, version = "1.0.0-1")
        insert("unknown1", version = "1.0.0-1")
        insert("attempted", version = "1.0.0-2")
        insert("unknown2", version = "1.0.0-1")
        insert("unknown3", version = "1.0.0-1")

        assertEquals(
            listOf(idOf("unknown1"), idOf("unknown2")),
            db.unknownTypeIdsNotAttemptedBy("1.0.0-2", afterId = 0, limit = 2)
        )
        assertEquals(
            listOf(idOf("unknown3")),
            db.unknownTypeIdsNotAttemptedBy("1.0.0-2", afterId = idOf("unknown2"), limit = 2)
        )

        db.setLastAttemptVersion(idOf("unknown1"), "1.0.0-2")
        assertEquals(
            listOf(idOf("unknown2"), idOf("unknown3")),
            db.unknownTypeIdsNotAttemptedBy("1.0.0-2", afterId = 0, limit = 50)
        )
    }

    @Test
    fun `a group unknown type row starting with a zero byte is replayed, not treated as newer format`() {
        insert("group", kind = Kind.UNKNOWN_TYPE, namespace = GROUP_MESSAGES_NAMESPACE, data = byteArrayOf(0, 2, 7, 7))

        db.stampNewerFormatAttempted("1.0.0-2")

        assertEquals("1.0.0-1", lastAttemptVersion("group"))
        assertEquals(listOf(idOf("group")), db.unknownTypeIdsNotAttemptedBy("1.0.0-2", afterId = 0, limit = 50))
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
    fun `the stats row is seeded and stays exact across inserts, updates and deletes`() {
        assertEquals(0L to 0L, stats())

        val placeholder = insertMmsRow(threadId = 1)
        insert("unknown", size = 10, placeholderId = placeholder)
        insert("newer1", kind = Kind.NEWER_FORMAT, size = 3)
        insert("newer2", kind = Kind.NEWER_FORMAT, size = 0)
        assertFalse(insert("newer1", kind = Kind.NEWER_FORMAT, size = 50))
        assertEquals((13L + 3 * UnsupportedMessageDatabase.ROW_OVERHEAD_BYTES) to 2L, stats())
        assertStatsExact()

        sqlite.execSQL("UPDATE ${UnsupportedMessageDatabase.TABLE_NAME} SET kind = 'unknownType', data = ? WHERE hash = 'newer1'", arrayOf(ByteArray(20)))
        assertEquals((30L + 3 * UnsupportedMessageDatabase.ROW_OVERHEAD_BYTES) to 1L, stats())
        assertStatsExact()

        db.delete(idOf("newer2"))
        assertStatsExact()

        sqlite.execSQL("DELETE FROM ${MmsDatabase.TABLE_NAME} WHERE _id = ?", arrayOf(placeholder))
        assertFalse(db.exists("unknown"))
        assertEquals((20L + UnsupportedMessageDatabase.ROW_OVERHEAD_BYTES) to 0L, stats())
        assertStatsExact()

        db.delete(idOf("newer1"))
        assertEquals(0L to 0L, stats())
    }

    @Test
    fun `the newer format count cap evicts the oldest newer format rows only`() {
        insert("unknown", kind = Kind.UNKNOWN_TYPE)
        repeat(5) { insert("newer$it", kind = Kind.NEWER_FORMAT) }

        db.enforceRetentionCaps(maxNewerFormatCount = 3)

        assertFalse(db.exists("newer0"))
        assertFalse(db.exists("newer1"))
        assertTrue(db.exists("newer2"))
        assertTrue(db.exists("newer3"))
        assertTrue(db.exists("newer4"))
        assertTrue(db.exists("unknown"))
        assertEquals(3L, stats().second)
        assertStatsExact()
    }

    @Test
    fun `the default newer format count cap is ten thousand`() {
        sqlite.execSQL("""
            WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 10001)
            INSERT INTO ${UnsupportedMessageDatabase.TABLE_NAME} (
                kind, swarm_public_key, namespace, hash, server_timestamp_ms, data, received_at_ms, last_attempt_version
            )
            SELECT 'newerFormat', '05', 0, 'h' || i, 0, x'00', 0, '1.0.0-1' FROM n
        """)

        db.enforceRetentionCaps()

        assertEquals(10_000L, count())
        assertFalse(db.exists("h1"))
        assertTrue(db.exists("h2"))
        assertStatsExact()
    }

    @Test
    fun `the byte budget counts the per row overhead, so many tiny rows are evicted`() {
        repeat(250) { insert("tiny$it", size = 1) }
        val rowCost = 1 + UnsupportedMessageDatabase.ROW_OVERHEAD_BYTES

        // Their data alone (250 bytes) fits easily
        db.enforceRetentionCaps(maxRetainedBytes = 120 * rowCost)

        assertEquals(120L, count())
        assertFalse(db.exists("tiny129"))
        assertTrue(db.exists("tiny130"))
        assertStatsExact()
    }

    @Test
    fun `the byte budget evicts newer format rows first, oldest first, until within budget`() {
        insert("unknownOld", kind = Kind.UNKNOWN_TYPE, size = 10)
        insert("newerOld", kind = Kind.NEWER_FORMAT, size = 10)
        insert("unknownNew", kind = Kind.UNKNOWN_TYPE, size = 10)
        insert("newerNew", kind = Kind.NEWER_FORMAT, size = 10)
        val rowCost = 10 + UnsupportedMessageDatabase.ROW_OVERHEAD_BYTES

        db.enforceRetentionCaps(maxRetainedBytes = 4 * rowCost)
        assertEquals(4L, count())

        db.enforceRetentionCaps(maxRetainedBytes = 3 * rowCost)
        assertFalse(db.exists("newerOld"))
        assertTrue(db.exists("newerNew"))

        db.enforceRetentionCaps(maxRetainedBytes = 2 * rowCost + 1)
        assertFalse(db.exists("newerNew"))
        assertTrue(db.exists("unknownOld"))
        assertTrue(db.exists("unknownNew"))

        db.enforceRetentionCaps(maxRetainedBytes = rowCost)
        assertFalse(db.exists("unknownOld"))
        assertTrue(db.exists("unknownNew"))
        assertStatsExact()
    }

    @Test
    fun `maintenance removes expired rows before applying the byte budget`() {
        insert("unknownOld", kind = Kind.UNKNOWN_TYPE, size = 10)
        insert("newerExpired", kind = Kind.NEWER_FORMAT, size = 10, expiresAtMs = 1_000L)
        insert("unknownNew", kind = Kind.UNKNOWN_TYPE, size = 10)

        db.enforceLimits(nowMs = 1_000L, maxRetainedBytes = 2L * (10 + UnsupportedMessageDatabase.ROW_OVERHEAD_BYTES))

        assertFalse(db.exists("newerExpired"))
        assertTrue(db.exists("unknownOld"))
        assertTrue(db.exists("unknownNew"))
        assertStatsExact()
    }

    private companion object {
        val SENDER_A = "05" + "aa".repeat(32)
        val SENDER_B = "05" + "bb".repeat(32)

        // Namespace.GROUP_MESSAGES(), which can't be read here as libsession's native library
        // doesn't load in JVM tests
        const val GROUP_MESSAGES_NAMESPACE = 11
    }
}
