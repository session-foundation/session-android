package org.thoughtcrime.securesms.pro.db

import android.content.Context
import androidx.collection.LruCache
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.transaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.pro.ProProof
import org.session.libsession.utilities.serializable.ByteArrayAsHexSerializer
import org.session.libsession.utilities.serializable.InstantAsMillisSerializer
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.Database
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.pro.api.ProRevocations
import org.thoughtcrime.securesms.util.asSequence
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

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

    private val mutableCurrentProProofChangesNotification = MutableSharedFlow<Unit>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val currentProProofChangesNotification: SharedFlow<Unit> get() = mutableCurrentProProofChangesNotification

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

    fun ensureValidRotatingKeys(now: Instant): ProRotatingKeys {
        writableDatabase.transaction {
            // First read the database to see if we have a valid key
            //language=roomsql
            val existingKeys: ProRotatingKeys? = readableDatabase.rawQuery("""
                SELECT value FROM pro_state
                WHERE name = ?
            """, STATE_NAME_ROTATING_KEYS).use { cursor ->
                if (cursor.moveToFirst()) {
                    json.decodeFromString(cursor.getString(0))
                } else {
                    null
                }
            }

            if (existingKeys != null && existingKeys.expiry > now) {
                return existingKeys
            }

            val keyPair = ED25519.generate(null)
            val newKeys = ProRotatingKeys(
                ed25519PubKey = keyPair.pubKey.data,
                ed25519PrivKey = keyPair.secretKey.data,
                expiry = now + ROTATING_KEY_VALIDITY_DAYS.days.toJavaDuration(),
            )

            execSQL("""
                INSERT OR REPLACE INTO pro_state (name, value)
                VALUES ('$STATE_NAME_ROTATING_KEYS', ?)
            """, arrayOf(json.encodeToString(newKeys)))

            return newKeys
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
                changes += stmt.executeUpdateDelete()
            }
        }

        if (changes > 0) {
            for (item in data) {
                cache.put(item.genIndexHash, Unit)
            }

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

    fun getCurrentProProof(): ProProof? {
        //language=roomsql
        return readableDatabase.rawQuery("""
            SELECT value FROM pro_state
            WHERE name = '?'
        """, STATE_PRO_PROOF).use { cursor ->
            if (cursor.moveToFirst()) {
                json.decodeFromString<ProProof>(cursor.getString(0))
            } else {
                null
            }
        }
    }

    fun updateCurrentProProof(proProof: ProProof?) {
        val changes = if (proProof != null) {
            writableDatabase.compileStatement("""
                INSERT INTO pro_state(name, value)
                VALUES (?, ?)
                ON CONFLICT DO UPDATE SET value=excluded.value WHERE value != excluded.value
            """).use { stmt ->
                stmt.bindString(1, STATE_PRO_PROOF)
                stmt.bindString(2, json.encodeToString(proProof))
                stmt.executeUpdateDelete() > 0
            }
        } else {
            writableDatabase.compileStatement("""
                DELETE FROM pro_state
                WHERE name = '$STATE_PRO_PROOF'
            """).use { stmt ->
                stmt.executeUpdateDelete() > 0
            }
        }

        if (changes) {
            mutableCurrentProProofChangesNotification.tryEmit(Unit)
        }
    }

    @Serializable
    class ProRotatingKeys(
        @Serializable(with = ByteArrayAsHexSerializer::class)
        val ed25519PubKey: ByteArray,

        @Serializable(with = ByteArrayAsHexSerializer::class)
        val ed25519PrivKey: ByteArray,

        @Serializable(with = InstantAsMillisSerializer::class)
        @SerialName(JSON_NAME_EXPIRY)
        val expiry: Instant,
    ) {
        companion object {
            const val JSON_NAME_EXPIRY = "expiry_ms"
        }
    }

    companion object {
        private const val TAG = "ProRevocationDatabase"

        private const val STATE_NAME_LAST_TICKET = "last_ticket"
        private const val STATE_NAME_ROTATING_KEYS = "rotating_private_key"

        private const val STATE_PRO_PROOF = "pro_proof"

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