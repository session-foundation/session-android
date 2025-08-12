package org.thoughtcrime.securesms.search

import android.content.Context
import android.database.Cursor
import android.database.MergeCursor
import com.annimon.stream.Stream
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.fromExternal
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.TextSecurePreferences.Companion.getLocalNumber
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.contacts.ContactAccessor
import org.thoughtcrime.securesms.database.CursorList
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.MmsSmsColumns
import org.thoughtcrime.securesms.database.SearchDatabase
import org.thoughtcrime.securesms.database.SessionContactDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.search.model.MessageResult
import org.thoughtcrime.securesms.search.model.SearchResult
import org.thoughtcrime.securesms.util.Stopwatch
import java.util.concurrent.Executor

// Class to manage data retrieval for search
class SearchRepository(
    context: Context,
    private val searchDatabase: SearchDatabase,
    private val threadDatabase: ThreadDatabase,
    private val groupDatabase: GroupDatabase,
    private val contactDatabase: SessionContactDatabase,
    private val contactAccessor: ContactAccessor,
    private val configFactory: ConfigFactory,
    private val executor: Executor
) {
    private val context: Context = context.applicationContext

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

    // Get set of blocked contact AccountIDs from the ConfigFactory
    private fun getBlockedContacts(): MutableSet<String> {
        val blockedContacts = mutableSetOf<String>()
        configFactory.withUserConfigs { userConfigs ->
            userConfigs.contacts.all().forEach { contact ->
                if (contact.blocked) {
                    blockedContacts.add(contact.id)
                }
            }
        }
        return blockedContacts
    }

    fun queryContacts(query: String): CursorList<Contact> {
        val excludingAddresses = getBlockedContacts()
        val contacts = contactDatabase.queryContactsByName(query, excludeUserAddresses = excludingAddresses)
        val contactList: MutableList<Address> = ArrayList()

        while (contacts.moveToNext()) {
            try {
                val contact = contactDatabase.contactFromCursor(contacts)
                val contactAccountId = contact.accountID
                val address = fromSerialized(contactAccountId)
                contactList.add(address)

                // Add the address in this query to the excluded addresses so the next query
                // won't get the same contact again
                excludingAddresses.add(contactAccountId)
            } catch (e: Exception) {
                Log.e("Loki", "Error building Contact from cursor in query", e)
            }
        }

        contacts.close()

        val addressThreads = threadDatabase.searchConversationAddresses(query, excludingAddresses)// filtering threads by looking up the accountID itself
        val individualRecipients = threadDatabase.getFilteredConversationList(contactList)
        if (individualRecipients == null && addressThreads == null) {
            return CursorList.emptyList()
        }
        val merged = MergeCursor(arrayOf(addressThreads, individualRecipients))

        return CursorList(merged, ContactModelBuilder(contactDatabase, threadDatabase))
    }

    private fun queryConversations(
        query: String,
    ): CursorList<GroupRecord> {
        val numbers = contactAccessor.getNumbersForThreadSearchFilter(context, query)
        val addresses = numbers.map { fromExternal(context, it) }

        val conversations = threadDatabase.getFilteredConversationList(addresses)
        return if (conversations != null)
            CursorList(conversations, GroupModelBuilder(threadDatabase, groupDatabase))
        else
            CursorList.emptyList()
    }

    private fun queryMessages(query: String): CursorList<MessageResult> {
        val blockedContacts = getBlockedContacts()
        val messages = searchDatabase.queryMessages(query, blockedContacts)
        return if (messages != null)
            CursorList(messages, MessageModelBuilder(context))
        else
            CursorList.emptyList()
    }

    private fun queryMessages(query: String, threadId: Long): CursorList<MessageResult?> {
        val blockedContacts = getBlockedContacts()
        val messages = searchDatabase.queryMessages(query, threadId, blockedContacts)
        return if (messages != null)
            CursorList(messages, MessageModelBuilder(context))
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

    private class ContactModelBuilder(
        private val contactDb: SessionContactDatabase,
        private val threadDb: ThreadDatabase
    ) : CursorList.ModelBuilder<Contact> {
        override fun build(cursor: Cursor): Contact {
            val threadRecord = threadDb.readerFor(cursor).current
            var contact =
                contactDb.getContactWithAccountID(threadRecord.recipient.address.toString())
            if (contact == null) {
                contact = Contact(threadRecord.recipient.address.toString())
                contact.threadID = threadRecord.threadId
            }
            return contact
        }
    }

    private class GroupModelBuilder(
        private val threadDatabase: ThreadDatabase,
        private val groupDatabase: GroupDatabase
    ) : CursorList.ModelBuilder<GroupRecord> {
        override fun build(cursor: Cursor): GroupRecord {
            val threadRecord = threadDatabase.readerFor(cursor).current
            return groupDatabase.getGroup(threadRecord.recipient.address.toGroupString()).get()
        }
    }

    private class MessageModelBuilder(private val context: Context) : CursorList.ModelBuilder<MessageResult> {
        override fun build(cursor: Cursor): MessageResult {
            val conversationAddress =
                fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(SearchDatabase.CONVERSATION_ADDRESS)))
            val messageAddress =
                fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(SearchDatabase.MESSAGE_ADDRESS)))
            val conversationRecipient = Recipient.from(context, conversationAddress, false)
            val messageRecipient = Recipient.from(context, messageAddress, false)
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