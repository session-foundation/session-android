package org.thoughtcrime.securesms.database

import android.content.Context
import androidx.sqlite.db.transaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsignal.protos.SignalServiceProtos
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import java.time.Instant
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class PendingMessageDatabase(
    @ApplicationContext context: Context,
    openHelper: Provider<SQLCipherOpenHelper>,
) : Database(context, openHelper) {
    private val mutableChangeNotification = MutableSharedFlow<Unit>()
    val changeNotification: SharedFlow<Unit> get() = mutableChangeNotification

    fun save(messages: Collection<Message>) {
        writableDatabase.transaction {
            val stmt = compileStatement("""
                INSERT OR IGNORE INTO pending_messages
                    (group_address, server_id, content, created_at, sent_at, sender)
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent())
            for (msg in messages) {
//                stmt.bindString(1, msg.repository.toSQLText())
            }
        }
    }


    data class Message(
        val repository: Repository,
        val serverId: String,
        val content: SignalServiceProtos.Content,
        val createdAt: Instant,
        val sentAt: Instant,
        val sender: Address.WithAccountId,
    )

    sealed interface Repository {
        data object Personal : Repository
        data class Group(val groupId: Address.Group) : Repository
        data class Community(val community: Address.Community) : Repository

        fun toSQLText(): String? = when (this) {
            is Personal -> null
            is Group -> groupId.toString()
            is Community -> community.toString()
        }

        companion object {
            fun fromSQLTextOrNull(sqlText: String?): Repository? {
                if (sqlText.isNullOrBlank()) {
                    return Personal
                }

                return when (val value = sqlText.toAddress()) {
                    is Address.Group -> Group(value)
                    is Address.Community -> Community(value)
                    else -> null
                }
            }
        }
    }

    companion object {
        val CREATE_TABLE = listOf(
            """
            CREATE TABLE pending_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                
                -- Where this message belongs to: null means own personal messages, or a group/community address
                repository TEXT NOT NULL,
                
                server_id TEXT,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                sent_at INTEGER,
                sender TEXT
            )
        """,
            """
               CREATE UNIQUE INDEX pending_messages_repo_server_id ON pending_messages (repository, server_id)
            """
        )
    }
}