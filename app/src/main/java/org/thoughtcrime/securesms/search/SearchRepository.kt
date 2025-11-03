package org.thoughtcrime.securesms.search

import android.content.Context
import android.database.Cursor
import dagger.hilt.android.qualifiers.ApplicationContext
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.concurrent.SignalExecutors
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.toGroupString
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.contacts.ContactAccessor
import org.thoughtcrime.securesms.database.CursorList
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.MmsSmsColumns
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.SearchDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.search.model.MessageResult
import org.thoughtcrime.securesms.search.model.SearchResult
import org.thoughtcrime.securesms.util.Stopwatch
import javax.inject.Inject
import javax.inject.Singleton

// Class to manage data retrieval for search
@Singleton
class SearchRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val searchDatabase: SearchDatabase,
    private val threadDatabase: ThreadDatabase,
    private val groupDatabase: GroupDatabase,
    private val contactAccessor: ContactAccessor,
    private val recipientRepository: RecipientRepository,
    private val conversationRepository: ConversationRepository,
    private val configFactory: ConfigFactoryProtocol,
    private val prefs: TextSecurePreferences,
) {
    private val executor = SignalExecutors.SERIAL

    fun query(query: String, callback: (SearchResult) -> Unit) {
        // If the sanitized search is empty then abort without search
        val cleanQuery = sanitizeQuery(query).trim { it <= ' ' }

        executor.execute {
            val timer =
                Stopwatch("FtsQuery")
            timer.split("clean")

            val contacts =
                queryContacts(cleanQuery)
            timer.split("Contacts")

            val conversations =
                queryConversations(cleanQuery)
            timer.split("Conversations")

            val messages = queryMessages(cleanQuery)
            timer.split("Messages")

            timer.stop(TAG)
            callback(
                SearchResult(
                    cleanQuery,
                    contacts,
                    conversations,
                    messages
                )
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

        executor.execute {
            val messages = queryMessages(cleanQuery, threadId)
            callback(messages)
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
    ): List<GroupRecord> {
        val numbers = contactAccessor.getNumbersForThreadSearchFilter(context, query)
        val addresses = numbers.map { fromSerialized(it) }

        return threadDatabase.getThreads(addresses)
            .map { groupDatabase.getGroup(it.recipient.address.toGroupString()).get() }
    }

    private fun queryMessages(query: String): CursorList<MessageResult> {
        val allConvo = conversationRepository.conversationListAddressesFlow.value
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

    interface Callback<E> {
        fun onResult(result: E)
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