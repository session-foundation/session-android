package org.thoughtcrime.securesms.contacts

import android.content.Context
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.util.AsyncLoader
import org.thoughtcrime.securesms.util.ContactUtilities
import org.thoughtcrime.securesms.util.LastMessageSentTimestamp

sealed class ContactSelectionListItem {
    class Contact(val recipient: Recipient) : ContactSelectionListItem()
}

class ContactSelectionListLoader(
    context: Context,
    val mode: Int,
    val filter: String?,
    private val deprecationManager: LegacyGroupDeprecationManager,
) : AsyncLoader<List<ContactSelectionListItem>>(context) {

    override fun loadInBackground(): List<ContactSelectionListItem> {
        val contacts = ContactUtilities.getAllContacts(context).asSequence()
            .filter {
                if(it.first.isLegacyGroupRecipient && deprecationManager.isDeprecated) return@filter false // ignore legacy group when deprecated
                if (filter.isNullOrEmpty()) return@filter true
                it.first.name.contains(filter.trim(), true) || it.first.address.toString().contains(filter.trim(), true)
            }.sortedWith(
                compareBy<Pair<Recipient, LastMessageSentTimestamp>> { !it.first.isLocalNumber } // NTS come first
                    .thenByDescending { it.second } // then order by last message time
            )
            .map { it.first }.toList()

        return getItems(contacts)
    }

    private fun getItems(contacts: List<Recipient>): List<ContactSelectionListItem> {
        val items = contacts.map {
            ContactSelectionListItem.Contact(it)
        }
        if (items.isEmpty()) return listOf()
        return items
    }
}