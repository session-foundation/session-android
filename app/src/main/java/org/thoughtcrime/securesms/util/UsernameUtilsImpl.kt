package org.thoughtcrime.securesms.util

import network.loki.messenger.libsession_util.getOrNull
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UsernameUtils
import org.session.libsession.utilities.truncateIdForDisplay
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.database.SessionContactDatabase
import org.thoughtcrime.securesms.dependencies.ConfigFactory

class UsernameUtilsImpl(
    private val prefs: TextSecurePreferences,
    private val configFactory: ConfigFactory,
    private val sessionContactDatabase: SessionContactDatabase,
): UsernameUtils {
    override fun getCurrentUsernameWithAccountIdFallback(): String = prefs.getProfileName()
        ?: truncateIdForDisplay( prefs.getLocalNumber() ?: "")

    override fun getCurrentUsername(): String? = prefs.getProfileName()

    override fun saveCurrentUserName(name: String) {
        configFactory.withMutableUserConfigs {
            it.userProfile.setName(name)
        }
    }

    override fun getContactNameWithAccountID(
        accountID: String,
        groupId: AccountId?,
        contactContext: Contact.ContactContext
    ): String {
        val contact = sessionContactDatabase.getContactWithAccountID(accountID)
        return getContactNameWithAccountID(contact, accountID, groupId, contactContext)
    }

    override fun getContactNameWithAccountID(
        contact: Contact?,
        accountID: String,
        groupId: AccountId?,
        contactContext: Contact.ContactContext)
    : String {
        // first attempt to get the name from the contact
        val userName: String? = contact?.displayName(contactContext)
            ?: if(groupId != null){
                configFactory.withGroupConfigs(groupId) { it.groupMembers.getOrNull(accountID)?.name }
            } else null

        // if the username is actually set to the user's accountId, truncate it
        val validatedUsername = if(userName == accountID) truncateIdForDisplay(accountID) else userName

        return validatedUsername ?: truncateIdForDisplay(accountID)
    }
}