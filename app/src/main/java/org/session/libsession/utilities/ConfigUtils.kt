package org.session.libsession.utilities

import network.loki.messenger.libsession_util.MutableContacts
import network.loki.messenger.libsession_util.util.Contact
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log

/**
 * This function will create the underlying contact if it doesn't exist before passing to [updateFunction]
 */
fun MutableContacts.upsertContact(accountId: String, updateFunction: Contact.() -> Unit = {}) {
    when {
        accountId.startsWith(IdPrefix.BLINDED.value) -> Log.w("Loki", "Trying to create a contact with a blinded ID prefix")
        accountId.startsWith(IdPrefix.UN_BLINDED.value) -> Log.w("Loki", "Trying to create a contact with an un-blinded ID prefix")
        accountId.startsWith(IdPrefix.BLINDEDV2.value) -> Log.w("Loki", "Trying to create a contact with a blindedv2 ID prefix")
        else -> getOrConstruct(accountId).let {
            updateFunction(it)
            set(it)
        }
    }
}