package org.thoughtcrime.securesms.database

import android.content.Context
import androidx.sqlite.db.transaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.util.asSequence
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ReceivedMessageHashDatabase @Inject constructor(
    @ApplicationContext context: Context,
    helper: Provider<SQLCipherOpenHelper>,
): Database(context, helper) {

    fun <M> dedupMessages(messages: Collection<M>, hash: (M) -> String, repositoryAddress: Address, namespace: Int): Sequence<M> {
        if (messages.isEmpty()) {
            return emptySequence()
        }

        val newHashes = readableDatabase.rawQuery("""
                SELECT value FROM json_each(?)
                WHERE NOT EXISTS (
                    SELECT 1 FROM received_message_hashes
                    WHERE repository_address = ?
                    AND namespace = ?
                    AND hash = value
                )
            """,
            JsonArray(messages.map { JsonPrimitive(hash(it)) }).toString(),
            repositoryAddress.address,
            namespace,
        ).use { cursor ->
            cursor.asSequence()
                .mapTo(hashSetOf()) { it.getString(0) }
        }

        return messages.asSequence().filter { hash(it) in newHashes }
    }

    fun removeHashesByRepo(repositoryAddress: Address) {
        writableDatabase.rawExecSQL("""
            DELETE FROM received_message_hashes
            WHERE repository_address = ?
        """, repositoryAddress.address)
    }

    fun removeHashesByNamespaces(namespaces: Iterable<Int>) {
        writableDatabase.rawExecSQL("""
            DELETE FROM received_message_hashes
            WHERE namespace IN (SELECT value FROM json_each(?))
        """, JsonArray(namespaces.map { JsonPrimitive(it) }).toString())
    }

    fun removeAll() {
        writableDatabase.rawExecSQL("DELETE FROM received_message_hashes WHERE 1")
    }

    fun addNewMessageHashes(hashes: Sequence<String>, repositoryAddress: Address, namespace: Int) {
        writableDatabase.transaction {
            val stmt = compileStatement("""
                INSERT OR IGNORE INTO received_message_hashes(repository_address, namespace, hash)
                VALUES (?, ?, ?)
            """)

            for (hash in hashes) {
                stmt.clearBindings()
                stmt.bindString(1, repositoryAddress.address)
                stmt.bindLong(2, namespace.toLong())
                stmt.bindString(3, hash)
                if (stmt.executeUpdateDelete() > 0) {
                    Log.d(TAG, "Added new received message hash for $repositoryAddress/$namespace: $hash")
                }
            }
        }
    }

    companion object {
        private const val TAG = "ReceivedMessageHashDatabase"

        const val CREATE_TABLE = """
          CREATE TABLE received_message_hashes(
            -- Where this message is stored, can be 05/03 or community addresses
            repository_address TEXT NOT NULL,
            namespace INTEGER NOT NULL DEFAULT 0,
            hash TEXT NOT NULL,
            
            PRIMARY KEY(repository_address, namespace, hash)
          ) WITHOUT ROWID
        """

        fun migrateFromOldTable(db: SQLiteDatabase) {
            db.rawQuery("""
                SELECT public_key, received_message_namespace, received_message_hash_values
                FROM session_received_message_hash_values_table
            """).use { cursor ->
                while (cursor.moveToNext()) {
                    val publicKey = cursor.getString(0)
                    val namespace = cursor.getInt(1)
                    for (hash in cursor.getString(2).splitToSequence("-")) {
                        db.rawExecSQL("""
                            INSERT OR IGNORE INTO received_message_hashes(repository_address, namespace, hash)
                            VALUES (?, ?, ?)
                        """, publicKey, namespace, hash)
                    }
                }
            }

            db.rawExecSQL("DROP TABLE session_received_message_hash_values_table")
        }
    }
}