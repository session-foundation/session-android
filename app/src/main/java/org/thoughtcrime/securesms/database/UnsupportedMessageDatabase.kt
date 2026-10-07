package org.thoughtcrime.securesms.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.transaction
import org.session.libsession.messaging.messages.UnsupportedMessage
import org.thoughtcrime.securesms.util.asSequence
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The raw swarm message for everything this client positively identified but can't process (see
 * [UnsupportedMessage]), retained so a newer client, or a future database import, can replay it
 * through its normal receive path.
 *
 * The table and column names are deliberately identical across the Android, iOS and Desktop
 * clients so an importer can read one shape from all of them, so don't rename them.
 *
 * A row is removed with its placeholder by a trigger on the `mms` table rather than by each delete
 * path, so no current or future way of deleting messages or conversations can leave rows behind.
 */
@Singleton
class UnsupportedMessageDatabase @Inject constructor(
    private val helper: Provider<SupportSQLiteOpenHelper>,
) {
    private val readableDatabase: SupportSQLiteDatabase get() = helper.get().readableDatabase
    private val writableDatabase: SupportSQLiteDatabase get() = helper.get().writableDatabase

    class Record(
        val id: Long,
        val kind: UnsupportedMessage.Kind,
        val swarmPublicKey: String,
        val namespace: Int,
        val hash: String,
        val sender: String?,
        val sentTimestampMs: Long?,
        val serverTimestampMs: Long,
        val serverExpiryMs: Long?,
        val data: ByteArray,
        val placeholderMessageId: Long?,
        val expiresAtMs: Long?,
        val receivedAtMs: Long,
        val lastAttemptVersion: String,
    )

    fun exists(hash: String): Boolean {
        //language=roomsql
        return readableDatabase.query("SELECT 1 FROM $TABLE_NAME WHERE hash = ?", arrayOf(hash))
            .use { it.moveToNext() }
    }

    /**
     * @return false if a record with the same hash already exists
     */
    fun insert(
        kind: UnsupportedMessage.Kind,
        swarmPublicKey: String,
        namespace: Int,
        hash: String,
        sender: String?,
        sentTimestampMs: Long?,
        serverTimestampMs: Long,
        serverExpiryMs: Long?,
        data: ByteArray,
        placeholderMessageId: Long?,
        expiresAtMs: Long?,
        receivedAtMs: Long,
        lastAttemptVersion: String,
    ): Boolean {
        //language=roomsql
        return writableDatabase.compileStatement("""
            INSERT OR IGNORE INTO $TABLE_NAME (
                kind, swarm_public_key, namespace, hash, sender, sent_timestamp_ms, server_timestamp_ms,
                server_expiry_ms, data, placeholder_message_id, expires_at_ms, received_at_ms,
                last_attempt_version
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).use { stmt ->
            stmt.bindString(1, kind.dbValue)
            stmt.bindString(2, swarmPublicKey)
            stmt.bindLong(3, namespace.toLong())
            stmt.bindString(4, hash)
            sender?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
            sentTimestampMs?.let { stmt.bindLong(6, it) } ?: stmt.bindNull(6)
            stmt.bindLong(7, serverTimestampMs)
            serverExpiryMs?.let { stmt.bindLong(8, it) } ?: stmt.bindNull(8)
            stmt.bindBlob(9, data)
            placeholderMessageId?.let { stmt.bindLong(10, it) } ?: stmt.bindNull(10)
            expiresAtMs?.let { stmt.bindLong(11, it) } ?: stmt.bindNull(11)
            stmt.bindLong(12, receivedAtMs)
            stmt.bindString(13, lastAttemptVersion)
            stmt.executeInsert() != -1L
        }
    }

    fun get(id: Long): Record? {
        //language=roomsql
        return readableDatabase.query("SELECT * FROM $TABLE_NAME WHERE id = ?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToNext()) return@use null

            val kind = UnsupportedMessage.Kind.fromDbValue(cursor.getString(cursor.getColumnIndexOrThrow("kind")))
                ?: return@use null

            fun nullableLong(column: String): Long? = cursor.getColumnIndexOrThrow(column)
                .takeUnless(cursor::isNull)
                ?.let(cursor::getLong)

            Record(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                kind = kind,
                swarmPublicKey = cursor.getString(cursor.getColumnIndexOrThrow("swarm_public_key")),
                namespace = cursor.getInt(cursor.getColumnIndexOrThrow("namespace")),
                hash = cursor.getString(cursor.getColumnIndexOrThrow("hash")),
                sender = cursor.getColumnIndexOrThrow("sender").takeUnless(cursor::isNull)?.let(cursor::getString),
                sentTimestampMs = nullableLong("sent_timestamp_ms"),
                serverTimestampMs = cursor.getLong(cursor.getColumnIndexOrThrow("server_timestamp_ms")),
                serverExpiryMs = nullableLong("server_expiry_ms"),
                data = cursor.getBlob(cursor.getColumnIndexOrThrow("data")),
                placeholderMessageId = nullableLong("placeholder_message_id"),
                expiresAtMs = nullableLong("expires_at_ms"),
                receivedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("received_at_ms")),
                lastAttemptVersion = cursor.getString(cursor.getColumnIndexOrThrow("last_attempt_version")),
            )
        }
    }

    /**
     * Marks every newer-format record as attempted by [version] without loading any of them: no
     * version of this client can decrypt one, so replaying it could only end where it started.
     */
    fun stampNewerFormatAttempted(version: String): Int {
        //language=roomsql
        return writableDatabase.compileStatement(
            "UPDATE $TABLE_NAME SET last_attempt_version = ? WHERE kind = ? AND last_attempt_version != ?"
        ).use { stmt ->
            stmt.bindString(1, version)
            stmt.bindString(2, UnsupportedMessage.Kind.NEWER_FORMAT.dbValue)
            stmt.bindString(3, version)
            stmt.executeUpdateDelete()
        }
    }

    /**
     * A page of unknown-type ids not yet attempted by [version], after [afterId]. Ids only, so the
     * retained data is never loaded more than a record at a time.
     */
    fun unknownTypeIdsNotAttemptedBy(version: String, afterId: Long, limit: Int): List<Long> {
        //language=roomsql
        return readableDatabase.query(
            """
                SELECT id FROM $TABLE_NAME
                WHERE kind = ? AND last_attempt_version != ? AND id > ?
                ORDER BY id
                LIMIT ?
            """,
            arrayOf<Any>(UnsupportedMessage.Kind.UNKNOWN_TYPE.dbValue, version, afterId, limit)
        ).use { cursor -> cursor.asSequence().map { it.getLong(0) }.toList() }
    }

    fun setLastAttemptVersion(id: Long, version: String) {
        //language=roomsql
        writableDatabase.execSQL(
            "UPDATE $TABLE_NAME SET last_attempt_version = ? WHERE id = ?",
            arrayOf<Any>(version, id)
        )
    }

    fun delete(id: Long) {
        //language=roomsql
        writableDatabase.execSQL("DELETE FROM $TABLE_NAME WHERE id = ?", arrayOf(id))
    }

    /**
     * Removes the records an unsend request targets. A record without a placeholder can't be found
     * through a message row, and replaying it after an update would restore a message its sender
     * deleted.
     */
    fun deleteForUnsend(sender: String, sentTimestampMs: Long) {
        //language=roomsql
        writableDatabase.execSQL(
            "DELETE FROM $TABLE_NAME WHERE sender = ? AND sent_timestamp_ms = ?",
            arrayOf<Any>(sender, sentTimestampMs)
        )
    }

    /**
     * Unlinks the record from its placeholder so the placeholder can be deleted without the trigger
     * taking the record with it, and gives the record its own [expiresAtMs] since nothing else owns
     * its expiry any more.
     */
    fun detachPlaceholder(id: Long, expiresAtMs: Long?) {
        //language=roomsql
        writableDatabase.execSQL(
            "UPDATE $TABLE_NAME SET placeholder_message_id = NULL, expires_at_ms = ? WHERE id = ?",
            arrayOf<Any?>(expiresAtMs, id)
        )
    }

    /**
     * Removes rows whose message has expired, then applies [enforceRetentionCaps].
     */
    fun enforceLimits(
        nowMs: Long,
        maxRetainedBytes: Long = MAX_RETAINED_BYTES,
        maxNewerFormatCount: Long = MAX_NEWER_FORMAT_COUNT,
    ) {
        writableDatabase.transaction {
            //language=roomsql
            execSQL("DELETE FROM $TABLE_NAME WHERE expires_at_ms <= ?", arrayOf(nowMs))
            applyRetentionCaps(maxRetainedBytes, maxNewerFormatCount)
        }
    }

    /**
     * Cheap enough to run after every insert: it reads the single [STATS_TABLE_NAME] row, and only
     * touches `unsupported_message` (through `unsupported_message_kind_id`) when a cap is exceeded.
     */
    fun enforceRetentionCaps(
        maxRetainedBytes: Long = MAX_RETAINED_BYTES,
        maxNewerFormatCount: Long = MAX_NEWER_FORMAT_COUNT,
    ) {
        writableDatabase.transaction { applyRetentionCaps(maxRetainedBytes, maxNewerFormatCount) }
    }

    /**
     * Newer-format rows go before unknown-type rows: anyone can deposit data with the newer-format
     * prefix in a one-to-one namespace, whereas an unknown type came from an authenticated sender.
     * Evicting an unknown type only drops its bytes; its placeholder stays, permanently unreadable.
     *
     * Every count and total here comes from [STATS_TABLE_NAME], which is only correct while its
     * triggers see every change to `unsupported_message`.
     */
    private fun SupportSQLiteDatabase.applyRetentionCaps(maxRetainedBytes: Long, maxNewerFormatCount: Long) {
        val excessNewerFormat = readStats().newerFormatCount - maxNewerFormatCount
        if (excessNewerFormat > 0) {
            //language=roomsql
            execSQL(
                """
                    DELETE FROM $TABLE_NAME WHERE id IN (
                        SELECT id FROM $TABLE_NAME WHERE kind = ? ORDER BY id ASC LIMIT ?
                    )
                """,
                arrayOf<Any>(UnsupportedMessage.Kind.NEWER_FORMAT.dbValue, excessNewerFormat)
            )
        }

        while (true) {
            var excessBytes = readStats().totalBytes - maxRetainedBytes
            if (excessBytes <= 0) return

            val batch = mutableListOf<Long>()

            for (kind in EVICTION_ORDER) {
                //language=roomsql
                query(
                    "SELECT id, length(data) FROM $TABLE_NAME WHERE kind = ? ORDER BY id ASC LIMIT ?",
                    arrayOf<Any>(kind.dbValue, EVICTION_BATCH_SIZE)
                ).use { cursor ->
                    while (excessBytes > 0 && cursor.moveToNext()) {
                        batch += cursor.getLong(0)
                        excessBytes -= cursor.getLong(1) + ROW_OVERHEAD_BYTES
                    }
                }

                if (batch.isNotEmpty()) break
            }

            // Only reachable if the stats row disagrees with the table, in which case looping
            // would never end
            if (batch.isEmpty()) return

            //language=roomsql
            execSQL(
                "DELETE FROM $TABLE_NAME WHERE id IN (SELECT value FROM json_each(?))",
                arrayOf(batch.joinToString(prefix = "[", postfix = "]"))
            )
        }
    }

    private class Stats(val totalBytes: Long, val newerFormatCount: Long)

    private fun SupportSQLiteDatabase.readStats(): Stats {
        //language=roomsql
        return query("SELECT total_bytes, newer_format_count FROM $STATS_TABLE_NAME WHERE id = 1").use {
            if (it.moveToNext()) Stats(it.getLong(0), it.getLong(1)) else Stats(0, 0)
        }
    }

    companion object {
        const val TABLE_NAME = "unsupported_message"

        const val STATS_TABLE_NAME = "unsupported_message_stats"

        /** Counted as `length(data) + ROW_OVERHEAD_BYTES` per row, so tiny rows still cost something */
        const val MAX_RETAINED_BYTES: Long = 256L * 1024 * 1024
        const val ROW_OVERHEAD_BYTES: Long = 256
        const val MAX_NEWER_FORMAT_COUNT: Long = 10_000

        private const val EVICTION_BATCH_SIZE = 100
        private val EVICTION_ORDER = listOf(UnsupportedMessage.Kind.NEWER_FORMAT, UnsupportedMessage.Kind.UNKNOWN_TYPE)

        //language=roomsql
        const val DELETE_FOR_PLACEHOLDER_SQL = "DELETE FROM $TABLE_NAME WHERE placeholder_message_id = ?"

        @JvmStatic
        fun createTable(db: SupportSQLiteDatabase) {
            //language=roomsql
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL,
                    swarm_public_key TEXT NOT NULL,
                    namespace INTEGER NOT NULL,
                    hash TEXT NOT NULL UNIQUE,
                    sender TEXT,
                    sent_timestamp_ms INTEGER,
                    server_timestamp_ms INTEGER NOT NULL,
                    server_expiry_ms INTEGER,
                    data BLOB NOT NULL,
                    placeholder_message_id INTEGER,
                    expires_at_ms INTEGER,
                    received_at_ms INTEGER NOT NULL,
                    last_attempt_version TEXT NOT NULL
                )
            """)

            //language=roomsql
            db.execSQL("CREATE INDEX IF NOT EXISTS unsupported_message_placeholder_index ON $TABLE_NAME (placeholder_message_id)")

            //language=roomsql
            db.execSQL("CREATE INDEX IF NOT EXISTS unsupported_message_expires_at_index ON $TABLE_NAME (expires_at_ms)")

            //language=roomsql
            db.execSQL("CREATE INDEX IF NOT EXISTS unsupported_message_sender_index ON $TABLE_NAME (sender, sent_timestamp_ms)")

            //language=roomsql
            db.execSQL("CREATE INDEX IF NOT EXISTS unsupported_message_kind_id ON $TABLE_NAME (kind, id)")

            //language=roomsql
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $STATS_TABLE_NAME (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    total_bytes INTEGER NOT NULL DEFAULT 0,
                    newer_format_count INTEGER NOT NULL DEFAULT 0
                )
            """)

            //language=roomsql
            db.execSQL("INSERT OR IGNORE INTO $STATS_TABLE_NAME (id, total_bytes, newer_format_count) VALUES (1, 0, 0)")

            val newerFormat = "'${UnsupportedMessage.Kind.NEWER_FORMAT.dbValue}'"

            //language=roomsql
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS unsupported_message_stats_insert
                AFTER INSERT ON $TABLE_NAME
                BEGIN
                    UPDATE $STATS_TABLE_NAME SET
                        total_bytes = total_bytes + length(NEW.data) + $ROW_OVERHEAD_BYTES,
                        newer_format_count = newer_format_count + (NEW.kind = $newerFormat)
                    WHERE id = 1;
                END
            """)

            //language=roomsql
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS unsupported_message_stats_delete
                AFTER DELETE ON $TABLE_NAME
                BEGIN
                    UPDATE $STATS_TABLE_NAME SET
                        total_bytes = total_bytes - length(OLD.data) - $ROW_OVERHEAD_BYTES,
                        newer_format_count = newer_format_count - (OLD.kind = $newerFormat)
                    WHERE id = 1;
                END
            """)

            //language=roomsql
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS unsupported_message_stats_update
                AFTER UPDATE OF data, kind ON $TABLE_NAME
                BEGIN
                    UPDATE $STATS_TABLE_NAME SET
                        total_bytes = total_bytes - length(OLD.data) + length(NEW.data),
                        newer_format_count = newer_format_count - (OLD.kind = $newerFormat) + (NEW.kind = $newerFormat)
                    WHERE id = 1;
                END
            """)

            // Placeholders are only ever MMS rows: SMS ids overlap with MMS ids, so there must be
            // no equivalent on the sms table.
            //language=roomsql
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS unsupported_message_placeholder_delete
                AFTER DELETE ON ${MmsDatabase.TABLE_NAME}
                BEGIN
                    DELETE FROM $TABLE_NAME WHERE placeholder_message_id = OLD.${MmsSmsColumns.ID};
                END
            """)
        }
    }
}
