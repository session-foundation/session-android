package org.thoughtcrime.securesms.database

import android.content.Context
import androidx.sqlite.db.transaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.session.libsession.snode.endpoint.Retrieve
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsignal.protos.SignalServiceProtos
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
) : Database(context, openHelper) {
    private val mutableChangeNotification = MutableSharedFlow<Address>(
        extraBufferCapacity = 25,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val changeNotification: SharedFlow<Address> get() = mutableChangeNotification

    fun saveSwarmMessages(
        swarmAddress: AccountId,
        namespace: Int,
        messages: Sequence<Retrieve.Message>
    ) {
        val changed = writableDatabase.transaction {
            val stmt = compileStatement(
                """
                INSERT OR IGNORE INTO received_messages
                    (repository_address, namespace, server_id, data, timestamp_ms)
                VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
            )

            var insertedSomething = false

            for (msg in messages) {
                stmt.clearBindings()
                stmt.bindString(1, swarmAddress.hexString)
                stmt.bindLong(2, namespace.toLong())
                stmt.bindString(3, msg.hash)
                stmt.bindString(4, msg.data)
                stmt.bindLong(5, msg.timestamp.toEpochMilli())

                insertedSomething = insertedSomething || stmt.executeUpdateDelete() > 0
            }

            insertedSomething
        }

        if (changed) {
            mutableChangeNotification.tryEmit(swarmAddress.toAddress())
        }
    }

    /**
     * Get all received pending messages sorted by timestamp ascending (oldest first).
     */
    fun getMessagesSorted(limit: Int): List<Message> {
        return readableDatabase.rawQuery(
            """
            SELECT namespace, server_id, data, timestamp_ms, repository_address
            FROM received_messages
            ORDER BY timestamp_ms ASC
            LIMIT ?
        """.trimIndent(), limit
        ).use { cursor ->
            cursor.asSequence()
                .map {
                    Message(
                        id = MessageId(
                            repositoryAddress = cursor.getString(5),
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

    fun removeMessages(ids: Sequence<MessageId>) {
        val changed = writableDatabase.transaction {
            val stmt = compileStatement(
                """
                DELETE FROM received_messages
                WHERE repository_address = ? AND namespace = ? AND server_id = ?
            """.trimIndent()
            )

            buildSet {
                for (id in ids) {
                    stmt.clearBindings()
                    stmt.bindString(1, id.repositoryAddress)
                    stmt.bindLong(2, id.namespace.toLong())
                    stmt.bindString(3, id.serverId)

                    if (stmt.executeUpdateDelete() > 0) {
                        add(id.repositoryAddress.toAddress())
                    }
                }
            }
        }

        for (addr in changed) {
            mutableChangeNotification.tryEmit(addr)
        }
    }

    data class MessageId(val repositoryAddress: String, val namespace: Int, val serverId: String)
    data class Message(val id: MessageId, val message: Retrieve.Message)

    companion object {
        @JvmStatic
        val CREATE_TABLE = arrayOf(
            """
        CREATE TABLE received_messages(
            --Where this message belongs to : either the user 's pub key, group' s key or a community address
            repository_address TEXT NOT NULL,

            --The namespace this message belongs to (only relevant for swarm messages, should be 0 for other type of repositories
            namespace INTEGER NOT NULL DEFAULT 0,

            --The value that can be used to identify this message on the server: hash for swarm messages and id for communities
            server_id TEXT NOT NULL,

            --The raw data that belongs to this repository (note: this can have different formats depending on the repository type)
            data TEXT NOT NULL,

            timestamp_ms INTEGER NOT NULL,

            PRIMARY KEY (repository_address, namespace, server_id)
        ) WITHOUT ROWID
        """,
            "CREATE INDEX idx_received_messages_repository_ts ON received_messages(repository_address, timestamp_ms)"
        )
    }
}