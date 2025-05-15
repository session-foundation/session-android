package org.session.libsession.utilities

import org.session.libsession.messaging.contacts.Contact
import org.session.libsignal.utilities.AccountId

interface UsernameUtils {
    fun getCurrentUsernameWithAccountIdFallback(): String

    fun getCurrentUsername(): String?

    fun saveCurrentUserName(name: String)

    fun getContactNameWithAccountID(
        accountID: String,
        groupId: AccountId? = null,
        contactContext: Contact.ContactContext = Contact.ContactContext.REGULAR
    ): String

    fun getContactNameWithAccountID(
        contact: Contact?,
        accountID: String,
        groupId: AccountId? = null,
        contactContext: Contact.ContactContext = Contact.ContactContext.REGULAR
    ): String
}