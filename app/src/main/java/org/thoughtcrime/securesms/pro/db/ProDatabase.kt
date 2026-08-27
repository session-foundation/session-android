package org.thoughtcrime.securesms.pro.db

import android.content.Context
import androidx.collection.LruCache
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.transaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.Database
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import network.loki.messenger.libsession_util.pro.GetProStatusResponse
import network.loki.messenger.libsession_util.pro.ProRevocationItem
import org.thoughtcrime.securesms.util.asSequence
import org.thoughtcrime.securesms.pro.ProSendStats
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ProDatabase @Inject constructor(
    @ApplicationContext context: Context,
    databaseHelper: Provider<SQLCipherOpenHelper>,
    private val json: Json,
) : Database(context, databaseHelper), ProSendStats.Store {

    private val cache = LruCache<String, Unit>(1000)

    private val mutableRevocationChangeNotification = MutableSharedFlow<Unit>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val revocationChangeNotification: SharedFlow<Unit> get() = mutableRevocationChangeNotification
    fun getLastRevocationTicket(): Long? {
        val cursor = readableDatabase.query("SELECT CAST(value AS INTEGER) FROM pro_state WHERE name = '$STATE_NAME_LAST_TICKET'")
        return cursor.use {
            if (it.moveToFirst()) {
                it.getLong(0)
            } else {
                null
            }
        }
    }

    fun updateRevocations(
        newTicket: Long,
        retainForSeconds: Long,
        now: Instant,
        data: List<ProRevocationItem>
    ) {
        var changes = 0
        // Memory-/storage-only local aging: keep each item until `seen + retain_for` (§4).
        val retainUntil = now.epochSecond + retainForSeconds

        writableDatabase.transaction {
            if (data.isNotEmpty()) {
                //language=roomsql
                compileStatement(
                    """
                INSERT INTO pro_revocations (revocation_tag, effective_ts, retain_until_ts)
                VALUES (?, ?, ?)
                ON CONFLICT DO UPDATE SET effective_ts=excluded.effective_ts, retain_until_ts=excluded.retain_until_ts
                WHERE effective_ts != excluded.effective_ts OR retain_until_ts != excluded.retain_until_ts
            """
                ).use { stmt ->
                    for (item in data) {
                        stmt.bindString(1, item.revocationTagHex)
                        stmt.bindLong(2, item.effectiveUnixTs)
                        stmt.bindLong(3, retainUntil)
                        changes += stmt.executeUpdateDelete()
                        stmt.clearBindings()
                    }
                }
            }

            //language=roomsql
            compileStatement("""
                INSERT OR REPLACE INTO pro_state (name, value)
                VALUES (?, ?)
            """).use { stmt ->
                stmt.bindString(1, STATE_NAME_LAST_TICKET)
                stmt.bindLong(2, newTicket)
                // Must be executed — binding alone writes nothing. Without this
                // getLastRevocationTicket() always returns null and every poll re-requests the
                // entire revocation list from ticket 0.
                stmt.executeInsert()
            }
        }

        // `cache` is a positive-only "known revoked" set, and isRevoked() trusts a hit without
        // re-checking the clock — so only cache items that are ALREADY effective (§4). Caching a
        // future-dated revocation here would apply it immediately and bypass the effective_ts check
        // in isRevoked()'s query; such an item is left to be picked up from the DB (and cached
        // there) once its effective_ts has passed.
        for (item in data) {
            if (item.effectiveUnixTs <= now.epochSecond) {
                cache.put(item.revocationTagHex, Unit)
            } else {
                // An update can also push effective_ts forward for a tag we cached on an earlier
                // poll; drop it so the DB's effective_ts check governs again.
                cache.remove(item.revocationTagHex)
            }
        }

        if (changes > 0) {
            mutableRevocationChangeNotification.tryEmit(Unit)
        }
    }

    fun pruneRevocations(now: Instant) {
        //language=roomsql
        val pruned = writableDatabase.rawQuery("""
            DELETE FROM pro_revocations
            WHERE retain_until_ts < ?
            RETURNING revocation_tag
        """, now.epochSecond).use { cursor ->
            cursor.asSequence()
                .map { it.getString(0) }
                .toList()
        }

        for (revocationTag in pruned) {
            cache.remove(revocationTag)
        }

        Log.d(TAG, "Pruned ${pruned.size} expired pro revocations")
    }

    fun isRevoked(revocationTag: String, now: Instant): Boolean {
        if (cache[revocationTag] != null) {
            return true
        }

        // A tag is revoked once the client clock has reached its effective_ts (§4); tag-match alone
        // is not enough. Local aging (retain_until_ts) is handled separately by pruneRevocations.
        //language=roomsql
        readableDatabase.query("""
            SELECT 1 FROM pro_revocations
            WHERE revocation_tag = ?1 AND ?2 >= effective_ts
            LIMIT 1
        """, arrayOf<Any>(revocationTag, now.epochSecond)).use { cursor ->
            if (cursor.moveToFirst()) {
                cache.put(revocationTag, Unit)
                return true
            }
            return false
        }
    }

    private val mutableProStatusChangeNotification = MutableSharedFlow<Unit>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val proStatusChangeNotification: SharedFlow<Unit> get() = mutableProStatusChangeNotification

    fun getProStatusAndLastUpdated(): Pair<GetProStatusResponse, Instant>? {
        return readableDatabase.query("""
            SELECT name, value FROM pro_state
            WHERE name IN (?, ?)
        """, arrayOf(STATE_PRO_STATUS, STATE_PRO_STATUS_UPDATED_AT)).use { cursor ->
            var details: GetProStatusResponse? = null
            var updatedAt: Instant? = null

            while (cursor.moveToNext()) {
                when (val name = cursor.getString(0)) {
                    // Tolerate a stale/incompatible cached blob (e.g. an older shape): drop it and let
                    // the next fetch repopulate, rather than throwing.
                    STATE_PRO_STATUS -> details =
                        runCatching { json.decodeFromString<GetProStatusResponse>(cursor.getString(1)) }.getOrNull()
                    STATE_PRO_STATUS_UPDATED_AT -> updatedAt = Instant.ofEpochMilli(cursor.getString(1).toLong())
                    else -> error("Unexpected state name $name")
                }
            }

            if (details != null && updatedAt != null) {
                details to updatedAt
            } else {
                null
            }
        }
    }

    /**
     * When a `get_pro_status` fetch was last **attempted**, successful or not.
     *
     * Separate from [getProStatusAndLastUpdated]'s timestamp, which cannot serve this purpose: that
     * value is written as a pair with the response blob and is unreadable without it, so a failed fetch
     * records nothing and a failing network goes unthrottled entirely.
     */
    fun getProStatusLastAttemptAt(): Instant? {
        return readableDatabase.query(
            "SELECT value FROM pro_state WHERE name = ?",
            arrayOf(STATE_PRO_STATUS_LAST_ATTEMPT_AT)
        ).use { cursor ->
            if (cursor.moveToFirst()) Instant.ofEpochMilli(cursor.getString(0).toLong()) else null
        }
    }

    /**
     * When the STARTUP GATE last attempted a fetch. Separate from [getProStatusLastAttemptAt]: the
     * gate's 24h interval must not be consumed by a routine refresh, and the 60s floor must not be
     * satisfied by a startup fetch from twenty hours ago.
     */
    fun getProStatusLastStartupFetchAttemptAt(): Instant? {
        return readableDatabase.query(
            "SELECT value FROM pro_state WHERE name = ?",
            arrayOf(STATE_PRO_STATUS_LAST_STARTUP_FETCH_ATTEMPT_AT)
        ).use { cursor ->
            if (cursor.moveToFirst()) Instant.ofEpochMilli(cursor.getString(0).toLong()) else null
        }
    }

    /** See [getProStatusLastStartupFetchAttemptAt]. Attempt-stamped, like the floor's key. */
    fun setProStatusLastStartupFetchAttemptAt(attemptedAt: Instant) {
        writableDatabase.compileStatement("""
            INSERT OR REPLACE INTO pro_state (name, value)
            VALUES (?, ?)
        """).use { stmt ->
            stmt.bindString(1, STATE_PRO_STATUS_LAST_STARTUP_FETCH_ATTEMPT_AT)
            stmt.bindString(2, attemptedAt.toEpochMilli().toString())
            // Must be executed — binding alone writes nothing, and the failure is silent.
            stmt.executeInsert()
        }
    }

    /** Records a fetch attempt. Deliberately no change notification — this is not display state. */
    fun setProStatusLastAttemptAt(attemptedAt: Instant) {
        writableDatabase.compileStatement("""
            INSERT OR REPLACE INTO pro_state (name, value)
            VALUES (?, ?)
        """).use { stmt ->
            stmt.bindString(1, STATE_PRO_STATUS_LAST_ATTEMPT_AT)
            stmt.bindString(2, attemptedAt.toEpochMilli().toString())
            // Must be executed — binding alone writes nothing, and the failure is silent.
            stmt.executeInsert()
        }
    }

    fun updateProStatus(proStatus: GetProStatusResponse, updatedAt: Instant) {
        val changes = writableDatabase.compileStatement("""
            INSERT INTO pro_state (name, value)
            VALUES (?, ?), (?, ?)
            ON CONFLICT DO UPDATE SET value=excluded.value
            WHERE value != excluded.value
        """).use { stmt ->
            stmt.bindString(1, STATE_PRO_STATUS)
            stmt.bindString(2, json.encodeToString(proStatus))
            stmt.bindString(3, STATE_PRO_STATUS_UPDATED_AT)
            stmt.bindString(4, updatedAt.toEpochMilli().toString())
            stmt.executeUpdateDelete()
        }

        if (changes > 0) {
            mutableProStatusChangeNotification.tryEmit(Unit)
        }
    }


    // --- ProSendStats.Store -------------------------------------------------------------------------
    //
    // The Pro "sent" stat counters, over the same pro_state key/value table as the rest of the scalar
    // Pro state, so no schema change is involved. Values are stored as TEXT like every other key here.

    override fun getProStatCount(name: String): Long? {
        return readableDatabase.query(
            "SELECT value FROM pro_state WHERE name = ?",
            arrayOf(name)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.toLongOrNull() else null
        }
    }

    /**
     * One statement, so a concurrent send cannot read the same value twice and lose an increment. A
     * get-then-set from Kotlin would be exactly that race.
     */
    override fun incrementProStatCount(name: String) {
        writableDatabase.compileStatement("""
            INSERT INTO pro_state (name, value)
            VALUES (?, '1')
            ON CONFLICT DO UPDATE SET value = CAST(CAST(value AS INTEGER) + 1 AS TEXT)
        """).use { stmt ->
            stmt.bindString(1, name)
            stmt.executeInsert()
        }
    }

    companion object {
        private const val TAG = "ProRevocationDatabase"

        private const val STATE_NAME_LAST_TICKET = "last_ticket"


        private const val STATE_PRO_STATUS = "pro_status"
        private const val STATE_PRO_STATUS_UPDATED_AT = "pro_status_updated_at"

        // Written on every ATTEMPT, unlike STATE_PRO_STATUS_UPDATED_AT which is written only alongside
        // a successful response. No migration needed: pro_state is a name/value table.
        private const val STATE_PRO_STATUS_LAST_ATTEMPT_AT = "pro_status_last_attempt_at"

        // The startup gate's 24h interval — a separate key, see getProStatusLastStartupFetchAttemptAt.
        private const val STATE_PRO_STATUS_LAST_STARTUP_FETCH_ATTEMPT_AT =
            "pro_status_last_startup_fetch_attempt_at"

        fun createTable(db: SupportSQLiteDatabase) {
            // A table to hold the list of pro revocations. This is the ORIGINAL (lokiV57) shipped
            // shape; `reshapeRevocationsForSeconds` (lokiV61) drops and recreates it with the
            // current seconds-based schema, so do NOT edit this to match the new columns — installs
            // that already ran lokiV57 must see the same schema here that they got when they shipped.
            //language=roomsql
            db.execSQL("""
                CREATE TABLE pro_revocations(
                    gen_index_hash TEXT NOT NULL PRIMARY KEY,
                    expiry_ms INTEGER NOT NULL
                ) WITHOUT ROWID
            """)

            // A table to hold state related to pro
            //language=roomsql
            db.execSQL("""
                CREATE TABLE pro_state(
                    name TEXT NOT NULL PRIMARY KEY,
                    value TEXT
                ) WITHOUT ROWID"""
            )
        }

        fun addEffectiveFromColumn(db: SupportSQLiteDatabase) {
            //language=roomsql
            db.execSQL("""
                ALTER TABLE pro_revocations
                ADD COLUMN effective_from_ms INTEGER NOT NULL DEFAULT 0
            """)
        }

        fun reshapeRevocationsForSeconds(db: SupportSQLiteDatabase) {
            // Pro is unreleased and the revocation list is re-polled, so drop and recreate the table
            // with the new seconds-based schema: revocation_tag / effective_ts / retain_until_ts
            // (replaces the old gen_index_hash / expiry_ms / effective_from_ms shape).
            //language=roomsql
            db.execSQL("DROP TABLE IF EXISTS pro_revocations")
            //language=roomsql
            db.execSQL("""
                CREATE TABLE pro_revocations(
                    revocation_tag TEXT NOT NULL PRIMARY KEY,
                    effective_ts INTEGER NOT NULL,
                    retain_until_ts INTEGER NOT NULL
                ) WITHOUT ROWID
            """)
        }
    }
}