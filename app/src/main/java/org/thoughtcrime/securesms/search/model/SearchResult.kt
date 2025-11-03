package org.thoughtcrime.securesms.search.model

import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.database.CursorList

/**
 * Represents an all-encompassing search result that can contain various result for different
 * subcategories.
 */
class SearchResult(
    val query: String = "",
    val contacts: List<Recipient> = emptyList(),
    val conversations: List<GroupRecord> = emptyList(),
    val messages: CursorList<MessageResult> = CursorList.emptyList<MessageResult>()
) {
    fun size(): Int {
        return contacts.size + conversations.size + messages.size
    }

    val isEmpty: Boolean
        get() = size() == 0

    fun close() {
        messages.close()
    }
}
