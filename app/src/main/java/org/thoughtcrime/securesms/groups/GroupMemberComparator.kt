package org.thoughtcrime.securesms.groups

import org.session.libsignal.utilities.AccountId

/**
 * A comparator for group members that ensures the current user appears first in the list,
 * then sorts the rest of the members by their pub key.
 */
class GroupMemberComparator(
    private val currentUserAccountId: AccountId
) : Comparator<AccountId> {
    override fun compare(o1: AccountId, o2: AccountId): Int {
        if (o1 == currentUserAccountId) {
            return -1 // Current user should come first
        } else if (o2 == currentUserAccountId) {
            return 1 // Current user should come first
        } else {
            return o1.hexString.compareTo(o2.hexString) // Compare other members normally
        }
    }
}