package org.thoughtcrime.securesms.database.model

import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.database.ThreadId

data class ThreadChanges(val id: ThreadId, val address: Address.Conversable) {
    override fun toString(): String {
        return "ThreadChanges(id=$id, address=${address.debugString})"
    }
}
