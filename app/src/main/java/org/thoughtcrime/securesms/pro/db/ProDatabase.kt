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
import org.thoughtcrime.securesms.pro.api.ProDetails
import org.thoughtcrime.securesms.pro.api.ProRevocations
import org.thoughtcrime.securesms.util.asSequence
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ProDatabase @Inject constructor(
    @ApplicationContext context: Context,
    databaseHelper: Provider<SQLCipherOpenHelper>,
    private val json: Json,
) : Database(context, databaseHelper) {

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
        data: List<ProRevocations.Item>
    ) {
        var changes = 0

        writableDatabase.transaction {
            if (data.isNotEmpty()) {
                //language=roomsql
                compileStatement(
                    """
                INSERT INTO pro_revocations (gen_index_hash, expiry_ms)
                VALUES (?, ?)
                ON CONFLICT DO UPDATE SET expiry_ms=excluded.expiry_ms WHERE expiry_ms != excluded.expiry_ms
            """
                ).use { stmt ->
                    for (item in data) {
                        stmt.bindString(1, item.genIndexHash)
                        stmt.bindLong(2, item.expiry.toEpochMilli())
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
            }
        }

        for (item in data) {
            cache.put(item.genIndexHash, Unit)
        }

        if (changes > 0) {
            mutableRevocationChangeNotification.tryEmit(Unit)
        }
    }

    fun pruneRevocations(now: Instant) {
        //language=roomsql
        val pruned = writableDatabase.rawQuery("""
            DELETE FROM pro_revocations
            WHERE expiry_ms < ?
            RETURNING gen_index_hash
        """, now.toEpochMilli()).use { cursor ->
            cursor.asSequence()
                .map { it.getString(0) }
                .toList()
        }

        for (genIndexHash in pruned) {
            cache.remove(genIndexHash)
        }

        Log.d(TAG, "Pruned ${pruned.size} expired pro revocations")
    }

    fun isRevoked(genIndexHash: String): Boolean {
        if (cache[genIndexHash] != null) {
            return true
        }

        //language=roomsql
        readableDatabase.query("""
            SELECT 1 FROM pro_revocations
            WHERE gen_index_hash = ?
            LIMIT 1
        """, arrayOf(genIndexHash)).use { cursor ->
            if (cursor.moveToFirst()) {
                cache.put(genIndexHash, Unit)
                return true
            }
            return false
        }
    }

    private val mutableProDetailsChangeNotification = MutableSharedFlow<Unit>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val proDetailsChangeNotification: SharedFlow<Unit> get() = mutableProDetailsChangeNotification

    fun getProDetailsAndLastUpdated(): Pair<ProDetails, Instant>? {
        return readableDatabase.rawQuery("""
            SELECT name, value FROM pro_state
            WHERE name IN (?, ?)
        """, STATE_PRO_DETAILS, STATE_PRO_DETAILS_UPDATED_AT).use { cursor ->
            var details: ProDetails? = null
            var updatedAt: Instant? = null

            while (cursor.moveToNext()) {
                when (val name = cursor.getString(0)) {
                    STATE_PRO_DETAILS -> details = json.decodeFromString(cursor.getString(1))
                    STATE_PRO_DETAILS_UPDATED_AT -> updatedAt = Instant.ofEpochMilli(cursor.getString(1).toLong())
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

    fun updateProDetails(proDetails: ProDetails, updatedAt: Instant) {
        val changes = writableDatabase.compileStatement("""
            INSERT INTO pro_state (name, value)
            VALUES (?, ?), (?, ?)
            ON CONFLICT DO UPDATE SET value=excluded.value
            WHERE value != excluded.value
        """).use { stmt ->
            stmt.bindString(1, STATE_PRO_DETAILS)
            stmt.bindString(2, json.encodeToString(proDetails))
            stmt.bindString(3, STATE_PRO_DETAILS_UPDATED_AT)
            stmt.bindString(4, updatedAt.toEpochMilli().toString())
            stmt.executeUpdateDelete()
        }

        if (changes > 0) {
            mutableProDetailsChangeNotification.tryEmit(Unit)
        }
    }


    companion object {
        private const val TAG = "ProRevocationDatabase"

        private const val STATE_NAME_LAST_TICKET = "last_ticket"


        private const val STATE_PRO_DETAILS = "pro_details"
        private const val STATE_PRO_DETAILS_UPDATED_AT = "pro_details_updated_at"

        private const val ROTATING_KEY_VALIDITY_DAYS = 15

        fun createTable(db: SupportSQLiteDatabase) {
            // A table to hold the list of pro revocations
            db.execSQL("""
                CREATE TABLE pro_revocations(
                    gen_index_hash TEXT NOT NULL PRIMARY KEY,
                    expiry_ms INTEGER NOT NULL
                ) WITHOUT ROWID
            """)

            // A table to hold state related to pro
            db.execSQL("""
                CREATE TABLE pro_state(
                    name TEXT NOT NULL PRIMARY KEY,
                    value TEXT
                ) WITHOUT ROWID"""
            )
        }
    }
}