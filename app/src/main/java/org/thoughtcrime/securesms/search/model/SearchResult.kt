package org.thoughtcrime.securesms.search.model

import android.database.ContentObserver
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.utilities.GroupRecord
import org.thoughtcrime.securesms.database.CursorList

/**
 * Represents an all-encompassing search result that can contain various result for different
 * subcategories.
 */
class SearchResult(
    val query: String,
    val contacts: CursorList<Contact>,
    val conversations: CursorList<GroupRecord>,
    val messages: CursorList<MessageResult>
) {
    fun size(): Int {
        return contacts.size + conversations.size + messages.size
    }

    val isEmpty: Boolean
        get() = size() == 0

    fun close() {
        contacts.close()
        conversations.close()
        messages.close()
    }

    companion object {
        val EMPTY: SearchResult = SearchResult(
            "",
            CursorList.emptyList<Contact>(),
            CursorList.emptyList<GroupRecord>(),
            CursorList.emptyList<MessageResult>()
        )
    }
}
