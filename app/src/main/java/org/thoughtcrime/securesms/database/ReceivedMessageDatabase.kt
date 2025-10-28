package org.thoughtcrime.securesms.database

import android.content.Context
import androidx.sqlite.db.transaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import org.session.libsession.snode.endpoint.Retrieve
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.util.asSequence
import java.time.Instant
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ReceivedMessageDatabase(
    @ApplicationContext context: Context,
    openHelper: Provider<SQLCipherOpenHelper>,
    private val json: Json,
) : Database(context, openHelper) {
    private val mutableChangeNotification = MutableSharedFlow<Address.Conversable>(
        extraBufferCapacity = 25,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val changeNotification: SharedFlow<Address.Conversable> get() = mutableChangeNotification

    fun saveSwarmMessages(
        swarmAccountId: AccountId,
        namespace: Int,
        messages: Sequence<Retrieve.Message>
    ) {
        val swarmAddress = checkNotNull(swarmAccountId.toAddress() as? Address.Conversable) {
            "Swarm messages can only be stored for conversable addresses"
        }

        val changed = writableDatabase.transaction {
            var insertedSomething = false

            compileStatement(
                """
                INSERT OR IGNORE INTO received_messages
                    (repository_address, namespace, server_id, data, timestamp_ms)
                VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
            ).use { stmt ->
                for (msg in messages) {
                    stmt.clearBindings()
                    stmt.bindString(1, swarmAddress.address)
                    stmt.bindLong(2, namespace.toLong())
                    stmt.bindString(3, msg.hash)
                    stmt.bindString(4, msg.data)
                    stmt.bindLong(5, msg.timestamp.toEpochMilli())

                    val numAffected = stmt.executeUpdateDelete()
                    insertedSomething = insertedSomething || numAffected > 0
                }
            }

            insertedSomething
        }

        if (changed) {
            mutableChangeNotification.tryEmit(swarmAddress)
        }
    }

    /**
     * Get all received pending messages sorted by timestamp ascending (oldest first).
     */
    fun getUnprocessedMessagesSorted(
        repositoryAddress: Address.Conversable,
        namespaces: List<Int>,
        limit: Int
    ): List<Message> {
        return readableDatabase.rawQuery(
            """
            SELECT namespace, server_id, data, timestamp_ms
            FROM received_messages
            ORDER BY timestamp_ms ASC
            WHERE repository_address = ? AND namespace IN (SELECT value FROM json_each(?)) AND unprocessed_data IS NOT NULL
            LIMIT ?
        """, repositoryAddress.address, limit, json.encodeToString(namespaces)
        ).use { cursor ->
            cursor.asSequence()
                .map {
                    Message(
                        id = MessageId(
                            repositoryAddress = repositoryAddress,
                            namespace = cursor.getInt(1),
                            serverId = cursor.getString(2)
                        ),
                        Retrieve.Message(
                            data = cursor.getString(3),
                            hash = cursor.getString(2),
                            timestamp = Instant.ofEpochMilli(cursor.getLong(4)),
                        )
                    )
                }
                .toList()
        }
    }


    fun markMessagesAsProcessed(ids: Sequence<MessageId>) {
        val changed = writableDatabase.transaction {
            compileStatement(
                """
                UPDATE received_messages
                SET unprocessed_data = NULL
                WHERE repository_address = ? AND namespace = ? AND server_id = ?
            """.trimIndent()
            ).use { stmt ->
                buildSet {
                    for (id in ids) {
                        stmt.clearBindings()
                        stmt.bindString(1, id.repositoryAddress.address)
                        stmt.bindLong(2, id.namespace.toLong())
                        stmt.bindString(3, id.serverId)

                        if (stmt.executeUpdateDelete() > 0) {
                            add(id.repositoryAddress)
                        }
                    }
                }
            }
        }

        for (address in changed) {
            mutableChangeNotification.tryEmit(address)
        }
    }

    data class MessageId(
        val repositoryAddress: Address.Conversable,
        val namespace: Int,
        val serverId: String
    )

    data class Message(val id: MessageId, val message: Retrieve.Message)

    companion object {
        @JvmStatic
        val CREATE_TABLE = arrayOf(
            """
        CREATE TABLE received_messages(
            -- Where this message belongs to : either the 05x user address, 03x group address or community addresses
            repository_address TEXT NOT NULL,

            -- The namespace this message belongs to (only relevant for swarm messages, should be 0 for other type of repositories
            namespace INTEGER NOT NULL DEFAULT 0,

            -- The value that can be used to identify this message on the server: hash for swarm messages and id for communities
            server_id TEXT NOT NULL,

            - -The "unprocessed" raw data, this can have different format depending on the repository type. If processed, this shall be set to NULL
            unprocessed_data TEXT,

            timestamp_ms INTEGER NOT NULL,

            PRIMARY KEY (repository_address, namespace, server_id)
        ) WITHOUT ROWID
        """,
            "CREATE INDEX idx_received_messages_repository_ts ON received_messages(repository_address, timestamp_ms)"
        )
    }
}