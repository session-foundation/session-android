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
                kind, swarm_public_key, namespace, hash, server_timestamp_ms, server_expiry_ms, data,
                placeholder_message_id, expires_at_ms, received_at_ms, last_attempt_version
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).use { stmt ->
            stmt.bindString(1, kind.dbValue)
            stmt.bindString(2, swarmPublicKey)
            stmt.bindLong(3, namespace.toLong())
            stmt.bindString(4, hash)
            stmt.bindLong(5, serverTimestampMs)
            serverExpiryMs?.let { stmt.bindLong(6, it) } ?: stmt.bindNull(6)
            stmt.bindBlob(7, data)
            placeholderMessageId?.let { stmt.bindLong(8, it) } ?: stmt.bindNull(8)
            expiresAtMs?.let { stmt.bindLong(9, it) } ?: stmt.bindNull(9)
            stmt.bindLong(10, receivedAtMs)
            stmt.bindString(11, lastAttemptVersion)
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
     * Ids only, so the retained data (up to [MAX_RETAINED_DATA_BYTES] in total) is never loaded at once.
     */
    fun idsNotAttemptedBy(version: String): List<Long> {
        //language=roomsql
        return readableDatabase.query(
            "SELECT id FROM $TABLE_NAME WHERE last_attempt_version != ? ORDER BY id",
            arrayOf(version)
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
     * Removes rows whose message has expired, then evicts the oldest rows until the retained data
     * fits within [maxRetainedDataBytes].
     *
     * Newer-format rows go before unknown-type rows: anyone can deposit data with the newer-format
     * prefix in a one-to-one namespace, whereas an unknown type came from an authenticated sender.
     * Evicting an unknown type only drops its bytes; its placeholder stays, permanently unreadable.
     */
    fun enforceLimits(nowMs: Long, maxRetainedDataBytes: Long = MAX_RETAINED_DATA_BYTES) {
        writableDatabase.transaction {
            //language=roomsql
            execSQL("DELETE FROM $TABLE_NAME WHERE expires_at_ms <= ?", arrayOf(nowMs))

            //language=roomsql
            var totalBytes = query("SELECT IFNULL(SUM(length(data)), 0) FROM $TABLE_NAME")
                .use { if (it.moveToNext()) it.getLong(0) else 0L }

            if (totalBytes <= maxRetainedDataBytes) return@transaction

            val idsToRemove = mutableListOf<Long>()

            //language=roomsql
            query("""
                SELECT id, length(data) FROM $TABLE_NAME
                ORDER BY (kind = ?) DESC, received_at_ms ASC, id ASC
            """, arrayOf(UnsupportedMessage.Kind.NEWER_FORMAT.dbValue)).use { cursor ->
                while (totalBytes > maxRetainedDataBytes && cursor.moveToNext()) {
                    idsToRemove += cursor.getLong(0)
                    totalBytes -= cursor.getLong(1)
                }
            }

            //language=roomsql
            compileStatement("DELETE FROM $TABLE_NAME WHERE id = ?").use { stmt ->
                for (id in idsToRemove) {
                    stmt.bindLong(1, id)
                    stmt.executeUpdateDelete()
                    stmt.clearBindings()
                }
            }
        }
    }

    companion object {
        const val TABLE_NAME = "unsupported_message"

        const val MAX_RETAINED_DATA_BYTES: Long = 256L * 1024 * 1024

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
