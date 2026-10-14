package org.thoughtcrime.securesms.database

import org.session.libsession.utilities.Address

/**
 * The address a message is stored against when it is not a sync copy naming its own target.
 *
 * Both message tables reject a duplicate by comparing `DATE_SENT`, `ADDRESS` and `THREAD_ID`, so this has
 * to agree with the address the same message was given when it was composed - otherwise our own message
 * coming back to us reads as a different one and is stored a second time.
 */
object MessageTargetAddress {

    /**
     * The conversation for anything group-shaped, the sender otherwise.
     *
     * [Address.LegacyGroup] is deliberately not treated as group-shaped here even though it is
     * [Address.GroupLike]: legacy groups are no longer supported, so their behaviour is left as it was.
     */
    fun of(threadAddress: Address, senderAddress: Address): Address = when (threadAddress) {
        is Address.Group, is Address.Community -> threadAddress
        else -> senderAddress
    }
}
