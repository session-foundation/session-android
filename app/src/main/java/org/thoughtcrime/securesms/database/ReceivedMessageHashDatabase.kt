package org.thoughtcrime.securesms.database

import android.content.Context
import androidx.collection.LruCache
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ReceivedMessageHashDatabase @Inject constructor(
    @ApplicationContext context: Context,
    databaseHelper: Provider<SQLCipherOpenHelper>,
    private val json: Json,
) : Database(context, databaseHelper) {

    private data class CacheKey(val publicKey: String, val namespace: Int, val hash: String)

    private val cache = LruCache<CacheKey, Unit>(1024)

    fun removeAllByNamespaces(vararg namespaces: Int) {
        synchronized(cache) {
            cache.evictAll()
        }

        //language=roomsql
        writableDatabase.rawExecSQL("""
            DELETE FROM received_messages
            WHERE namespace IN (SELECT value FROM json_each(?))
        """, json.encodeToString(namespaces))
    }

    fun removeAllByPublicKey(publicKey: String) {
        synchronized(cache) {
            cache.evictAll()
        }

        //language=roomsql
        writableDatabase.rawExecSQL("""
            DELETE FROM received_messages
            WHERE swarm_pub_key = ?
        """, publicKey)
    }

    fun removeAll() {
        synchronized(cache) {
            cache.evictAll()
        }

        //language=roomsql
        writableDatabase.rawExecSQL("DELETE FROM received_messages WHERE 1")
    }

    /**
     * Checks if the given [hash] is already present in the database for the given
     * [swarmPublicKey] and [namespace]. If not, adds it to the database.
     *
     * This implementation is atomic.
     *
     * @return true if the hash was already in the db
     */
    fun checkOrUpdateDuplicateState(
        swarmPublicKey: String,
        namespace: Int,
        hash: String
    ): Boolean {
        val key = CacheKey(swarmPublicKey, namespace, hash)
        synchronized(cache) {
            if (cache[key] != null) {
                return true
            }
        }

        //language=roomsql
        return writableDatabase.compileStatement("""
            INSERT OR IGNORE INTO received_messages (swarm_pub_key, namespace, hash)
            VALUES (?, ?, ?)
        """).use { stmt ->
            stmt.bindString(1, swarmPublicKey)
            stmt.bindLong(2, namespace.toLong())
            stmt.bindString(3, hash)
            stmt.executeUpdateDelete() == 0
        }.also {
            synchronized(cache) {
                cache.put(key, Unit)
            }
        }
    }

    companion object {
        fun createAndMigrateTable(db: SupportSQLiteDatabase) {
            //language=roomsql
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS received_messages(
                    swarm_pub_key TEXT NOT NULL,
                    namespace INTEGER NOT NULL,
                    hash TEXT NOT NULL,
                    PRIMARY KEY (swarm_pub_key, namespace, hash)
                ) WITHOUT ROWID;
            """)

            //language=roomsql
            db.compileStatement("""
                INSERT OR IGNORE INTO received_messages (swarm_pub_key, namespace, hash)
                VALUES (?, ?, ?)
            """).use { stmt ->

                //language=roomsql
                db.query("""
                    SELECT public_key, received_message_hash_values, received_message_namespace
                    FROM session_received_message_hash_values_table
                """, arrayOf()).use { cursor ->
                    while (cursor.moveToNext()) {
                        val publicKey = cursor.getString(0)
                        val hashValuesString = cursor.getString(1)
                        val namespace = cursor.getInt(2)

                        val hashValues = hashValuesString.splitToSequence('-')

                        for (hash in hashValues) {
                            stmt.bindString(1, publicKey)
                            stmt.bindLong(2, namespace.toLong())
                            stmt.bindString(3, hash)
                            stmt.execute()
                            stmt.clearBindings()
                        }
                    }
                }
            }

            //language=roomsql
            db.execSQL("DROP TABLE session_received_message_hash_values_table")
        }
    }
}