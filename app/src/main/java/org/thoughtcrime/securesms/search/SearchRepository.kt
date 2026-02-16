package org.thoughtcrime.securesms.search

import android.database.Cursor
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.util.GroupInfo
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.recipients.displayName
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.database.CursorList
import org.thoughtcrime.securesms.database.MmsSmsColumns
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SearchDatabase
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.search.model.MessageResult
import org.thoughtcrime.securesms.search.model.SearchResult
import javax.inject.Inject
import javax.inject.Singleton

// Class to manage data retrieval for search
@Singleton
class SearchRepository @Inject constructor(
    private val searchDatabase: SearchDatabase,
    private val recipientRepository: RecipientRepository,
    private val conversationRepository: Lazy<ConversationRepository>,
    private val configFactory: ConfigFactoryProtocol,
    @param:ManagerScope private val scope: CoroutineScope,
) {
    private val searchSemaphore = Semaphore(1)

    suspend fun query(query: String): SearchResult = withContext(Dispatchers.Default) {
        searchSemaphore.withPermit {
            // If the sanitized search is empty then abort without search
            val cleanQuery = sanitizeQuery(query).trim { it <= ' ' }

            val contacts = queryContacts(cleanQuery)
            val conversations = queryConversations(cleanQuery)
            val messages = queryMessages(cleanQuery)

            SearchResult(
                cleanQuery,
                contacts,
                conversations,
                messages
            )
        }
    }

    fun query(query: String, threadId: Long, callback: (CursorList<MessageResult?>) -> Unit) {
        // If the sanitized search query is empty then abort the search
        val cleanQuery = sanitizeQuery(query).trim { it <= ' ' }
        if (cleanQuery.isEmpty()) {
            callback(CursorList.emptyList())
            return
        }

        scope.launch {
            searchSemaphore.withPermit {
                callback(queryMessages(cleanQuery, threadId))
            }
        }
    }

    fun queryContacts(searchName: String? = null): List<Recipient> {
        return configFactory.withUserConfigs { configs ->
            (configs.contacts.all().asSequence()
                .filter { !it.blocked &&
                        // If we are searching for contacts - we will include the unapproved ones
                        (!searchName.isNullOrBlank() || it.approved)
                }
                .map { it.id.toAddress() }) +
                    configs.contacts.allBlinded().asSequence()
                        .map {
                            Address.CommunityBlindedId(
                                serverUrl = it.communityServer,
                                blindedId = Address.Blinded(AccountId(it.id))
                            )
                        }
        }
            .map(recipientRepository::getRecipientSync)
            .filterNot { it.isSelf } // It is possible to have self in the contacts list so we need to weed it out
            .filter {
                searchName == null ||
                    when (it.data) {
                        // Search contacts by both nickname and name
                        is RecipientData.Contact -> {
                            it.data.nickname?.contains(searchName, ignoreCase = true) == true ||
                                    it.data.name.contains(searchName, ignoreCase = true)
                        }

                        is RecipientData.BlindedContact -> {
                            it.data.displayName.contains(searchName, ignoreCase = true)
                        }

                        else -> error("We should only get contacts data here but got ${it.data.javaClass}")
                    }
            }
            .toList()
    }

    private fun queryConversations(
        query: String,
    ) : List<Recipient> {
        if(query.isEmpty()) return emptyList()

        return configFactory.withUserConfigs { configs ->
            configs.userGroups.all()
        }.asSequence()
            .mapNotNull { group ->
                when (group) {
                    is GroupInfo.ClosedGroupInfo -> {
                        if(group.invited) null // do not show groups V2 we have not yet accepted
                        else recipientRepository.getRecipientSync(
                            Address.Group(AccountId(group.groupAccountId))
                        )
                    }

                    is GroupInfo.LegacyGroupInfo -> {
                        recipientRepository.getRecipientSync(
                            Address.LegacyGroup(group.accountId)
                        )
                    }

                    is GroupInfo.CommunityGroupInfo -> {
                        recipientRepository.getRecipientSync(
                            Address.Community(
                                serverUrl = group.community.baseUrl,
                                room = group.community.room
                            )
                        )
                    }
                }
            }
            .filter { group ->
                group.displayName().contains(query, ignoreCase = true)
            }
            .toList()
    }

    private suspend fun queryMessages(query: String): CursorList<MessageResult> {
        val allConvo = conversationRepository.get().conversationListAddressesFlow.first()
        val messages = searchDatabase.queryMessages(query, allConvo)
        return if (messages != null)
            CursorList(messages, MessageModelBuilder())
        else
            CursorList.emptyList()
    }

    private fun queryMessages(query: String, threadId: Long): CursorList<MessageResult?> {
        val messages = searchDatabase.queryMessages(query, threadId)
        return if (messages != null)
            CursorList(messages, MessageModelBuilder())
        else
            CursorList.emptyList()
    }

    /**
     * Unfortunately [DatabaseUtils.sqlEscapeString] is not sufficient for our purposes.
     * MATCH queries have a separate format of their own that disallow most "special" characters.
     *
     * Also, SQLite can't search for apostrophes, meaning we can't normally find words like "I'm".
     * However, if we replace the apostrophe with a space, then the query will find the match.
     */
    private fun sanitizeQuery(query: String): String {
        val out = StringBuilder()

        for (i in 0..<query.length) {
            val c = query[i]
            if (!BANNED_CHARACTERS.contains(c)) {
                out.append(c)
            } else if (c == '\'') {
                out.append(' ')
            }
        }

        return out.toString()
    }

    private inner class MessageModelBuilder() : CursorList.ModelBuilder<MessageResult> {
        override fun build(cursor: Cursor): MessageResult {
            val conversationAddress =
                fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(SearchDatabase.CONVERSATION_ADDRESS)))
            val messageAddress =
                fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(SearchDatabase.MESSAGE_ADDRESS)))
            val conversationRecipient = recipientRepository.getRecipientSync(conversationAddress)
            val messageRecipient = recipientRepository.getRecipientSync(messageAddress)
            val body = cursor.getString(cursor.getColumnIndexOrThrow(SearchDatabase.SNIPPET))
            val sentMs =
                cursor.getLong(cursor.getColumnIndexOrThrow(MmsSmsColumns.NORMALIZED_DATE_SENT))
            val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(MmsSmsColumns.THREAD_ID))

            return MessageResult(conversationRecipient, messageRecipient, body, threadId, sentMs)
        }
    }

    companion object {
        private val TAG: String = SearchRepository::class.java.simpleName

        private val BANNED_CHARACTERS: MutableSet<Char> = HashSet()

        init {
            // Construct a list containing several ranges of invalid ASCII characters
            // See: https://www.ascii-code.com/
            for (i in 33..47) {
                BANNED_CHARACTERS.add(i.toChar())
            } // !, ", #, $, %, &, ', (, ), *, +, ,, -, ., /

            for (i in 58..64) {
                BANNED_CHARACTERS.add(i.toChar())
            } // :, ;, <, =, >, ?, @

            for (i in 91..96) {
                BANNED_CHARACTERS.add(i.toChar())
            } // [, \, ], ^, _, `

            for (i in 123..126) {
                BANNED_CHARACTERS.add(i.toChar())
            } // {, |, }, ~
        }
    }
}