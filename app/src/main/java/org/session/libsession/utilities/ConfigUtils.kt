package org.session.libsession.utilities

import network.loki.messenger.libsession_util.MutableContacts
import network.loki.messenger.libsession_util.util.Contact

/**
 * This function will create the underlying contact if it doesn't exist before passing to [updateFunction]
 */
inline fun <T> MutableContacts.upsertContact(address: Address.Standard, updateFunction: Contact.() -> T): T {
    return getOrConstruct(address.accountId.hexString).let {
        val r = updateFunction(it)
        set(it)
        r
    }
}

inline fun MutableContacts.updateContact(address: Address.Standard, updateFunction: Contact.() -> Unit): Boolean {
    return get(address.accountId.hexString)?.let {
        updateFunction(it)
        set(it)
        true
    } ?: false
}

/**
 * The minimal number of days to set on a conversation's lastRead timestamp, when
 * the conversation is created without a known lastRead timestamp.
 *
 * This is to prevent the conversation data from getting pruned by not having a recent
 * lastRead timestamp.
 */
const val MIN_CONVERSATION_LAST_READ_DAYS = 14