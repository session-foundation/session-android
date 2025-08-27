package org.session.libsession.messaging.open_groups

import org.session.libsignal.utilities.AccountId

data class GroupMemberAndRole(
    val memberId: AccountId,
    val role: GroupMemberRole
)

enum class GroupMemberRole(val canModerate: Boolean = false, val shouldShowAdminCrown: Boolean = false) {
    STANDARD,
    ZOOMBIE,
    MODERATOR(canModerate = true, shouldShowAdminCrown = true),
    ADMIN(canModerate = true, shouldShowAdminCrown = true),
    HIDDEN_MODERATOR(canModerate = true, shouldShowAdminCrown = false),
    HIDDEN_ADMIN(canModerate = true, shouldShowAdminCrown = false),
}
