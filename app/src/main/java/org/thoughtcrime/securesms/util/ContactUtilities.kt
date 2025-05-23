package org.thoughtcrime.securesms.util

import android.content.Context
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.dependencies.DatabaseComponent

typealias LastMessageSentTimestamp = Long
object ContactUtilities {

    @JvmStatic
    fun getAllContacts(context: Context): Set<Pair<Recipient, LastMessageSentTimestamp>> {
        val threadDatabase = DatabaseComponent.get(context).threadDatabase()
        val cursor = threadDatabase.conversationList
        val result = mutableSetOf<Pair<Recipient, LastMessageSentTimestamp>>()
        threadDatabase.readerFor(cursor).use { reader ->
            while (reader.next != null) {
                val thread = reader.current
                result.add(Pair(thread.recipient, thread.lastMessage?.timestamp ?: 0))
            }
        }
        return result
    }

}