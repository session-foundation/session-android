package org.thoughtcrime.securesms.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.transaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.util.asSequence
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ReceivedMessageHashDatabase @Inject constructor(
    @ApplicationContext context: Context,
    databaseHelper: Provider<SQLCipherOpenHelper>,
    private val json: Json,
) : Database(context, databaseHelper) {
    fun removeAllByNamespaces(vararg namespaces: Int) {
        //language=roomsql
        writableDatabase.rawExecSQL("""
            DELETE FROM received_messages
            WHERE namespace IN (SELECT value FROM json_each(?))
        """, json.encodeToString(namespaces))
    }

    fun removeAllByPublicKey(publicKey: String) {
        //language=roomsql
        writableDatabase.rawExecSQL("""
            DELETE FROM received_messages
            WHERE swarm_pub_key = ?
        """, publicKey)
    }

    fun removeAll() {
        //language=roomsql
        writableDatabase.rawExecSQL("DELETE FROM received_messages WHERE 1")
    }

    private fun removeDuplicatedHashes(
        swarmPublicKey: String,
        namespace: Int,
        hashes: Collection<String>
    ): Set<String> {
        val hashesAsJsonText = json.encodeToString(hashes)

        //language=roomsql
        return writableDatabase.rawQuery("""
            SELECT ea.value FROM json_each(?) ea
            WHERE NOT EXISTS (
                SELECT 1 FROM received_messages m
                WHERE m.swarm_pub_key = ? AND m.namespace = ? AND m.hash = ea.value
            )
        """, hashesAsJsonText, swarmPublicKey, namespace).use { cursor ->
            cursor.asSequence()
                .mapTo(hashSetOf()) { it.getString(0) }
        }
    }

    /**
     * Filters out messages with duplicate hashes from the provided list, performs the given action
     * on the new messages, and then adds the new message hashes to the database.
     */
    fun <M, T> withRemovedDuplicateMessages(
        swarmPublicKey: String,
        namespace: Int,
        messages: List<M>,
        messageHashGetter: (M) -> String,
        performOnNewMessages: (List<M>) -> T,
    ): T {
        return writableDatabase.transaction {
            val newMessageHashes = removeDuplicatedHashes(
                swarmPublicKey = swarmPublicKey,
                namespace = namespace,
                hashes = messages.map(messageHashGetter)
            )

            val ret = performOnNewMessages(
                messages.filter { m -> messageHashGetter(m) in newMessageHashes }
            )

            addHashes(
                swarmPublicKey = swarmPublicKey,
                namespace = namespace,
                hashes = newMessageHashes
            )

            ret
        }
    }

    private fun addHashes(swarmPublicKey: String, namespace: Int, hashes: Collection<String>) {
        //language=roomsql
        writableDatabase.rawExecSQL("""
            INSERT OR IGNORE INTO received_messages (swarm_pub_key, namespace, hash)
            SELECT ?, ?, value FROM json_each(?)
        """, swarmPublicKey, namespace, json.encodeToString(hashes))
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