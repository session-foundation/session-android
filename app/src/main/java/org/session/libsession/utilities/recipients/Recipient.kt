package org.session.libsession.utilities.recipients

import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_PINNED
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.messaging.open_groups.GroupMemberRole
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.isCommunity
import org.session.libsession.utilities.isCommunityInbox
import org.session.libsession.utilities.isGroup
import org.session.libsession.utilities.isGroupOrCommunity
import org.session.libsession.utilities.isGroupV2
import org.session.libsession.utilities.isLegacyGroup
import org.session.libsession.utilities.isStandard
import org.thoughtcrime.securesms.database.model.NotifyType
import java.time.Instant

data class Recipient(
    val address: Address,
    val data: RecipientData,
    val mutedUntil: Instant? = null,
    val autoDownloadAttachments: Boolean? = null,
    val notifyType: NotifyType = NotifyType.ALL,
) {
    /**
     * Whether this recipient is ourself. Note that this check only applies to the standard
     * address.
     */
    val isLocalNumber: Boolean get() = address is Address.Standard && isSelf

    /**
     * Check if this recipient is ourself, this property will handle blinding correctly.
     */
    val isSelf: Boolean get() = data is RecipientData.Self

    /**
     * The role of the current user in the group or community. If the recipient is not a group or community,
     * it will always return [GroupMemberRole.STANDARD].
     */
    val currentUserRole: GroupMemberRole get() = when (data) {
        is RecipientData.Group -> if (data.partial.isAdmin) GroupMemberRole.ADMIN else GroupMemberRole.STANDARD
        is RecipientData.Community -> when {
            data.roomInfo?.admin == true -> GroupMemberRole.ADMIN
            data.roomInfo?.moderator == true -> GroupMemberRole.MODERATOR
            else -> GroupMemberRole.STANDARD
        }
        is RecipientData.LegacyGroup -> if (data.isCurrentUserAdmin) GroupMemberRole.ADMIN else GroupMemberRole.STANDARD
        else -> GroupMemberRole.STANDARD
    }

    val isGroupOrCommunityRecipient: Boolean get() = address.isGroupOrCommunity
    val isCommunityRecipient: Boolean get() = address.isCommunity
    val isCommunityInboxRecipient: Boolean get() = address.isCommunityInbox
    val isGroupV2Recipient: Boolean get() = address.isGroupV2
    val isLegacyGroupRecipient: Boolean get() = address.isLegacyGroup
    val isStandardRecipient: Boolean get() = address.isStandard
    val is1on1: Boolean get() = !isLocalNumber && !address.isGroupOrCommunity
    val isGroupRecipient: Boolean get() = address.isGroup

    val avatar: RemoteFile? get() = data.avatar
    val expiryMode: ExpiryMode get() = when (data) {
        is RecipientData.Self -> data.expiryMode
        is RecipientData.Contact -> data.expiryMode
        is RecipientData.Group -> data.partial.expiryMode
        else -> ExpiryMode.NONE
    }

    val approved: Boolean get() = when {
        isSelf ||
            address is Address.Community ||
            address is Address.LegacyGroup ||
            address is Address.CommunityBlindedId -> true

        data is RecipientData.Contact -> data.approved
        data is RecipientData.Group -> data.partial.approved

        else -> false
    }

    val proStatus: ProStatus get() = data.proStatus

    val approvedMe: Boolean get() {
        return when (data) {
            is RecipientData.Contact -> data.approvedMe

            is RecipientData.Generic,
            is RecipientData.BlindedContact -> false

            is RecipientData.Self,
            is RecipientData.Group,
            is RecipientData.Community,
            is RecipientData.LegacyGroup -> true
        }
    }

    val blocked: Boolean get() = when (data) {
        is RecipientData.Contact -> data.blocked
        else -> false
    }

    val priority: Long get() = data.priority

    val isPinned: Boolean get() = priority == PRIORITY_PINNED

    @JvmOverloads
    fun isMuted(now: Instant = Instant.now()): Boolean {
        return mutedUntil?.isAfter(now) == true
    }

    val showCallMenu: Boolean
        get() = !isGroupOrCommunityRecipient && approvedMe && approved

    val mutedUntilMills: Long?
        get() = mutedUntil?.toEpochMilli()

    val acceptsBlindedCommunityMessageRequests: Boolean
        get() = when (data) {
            is RecipientData.BlindedContact -> data.acceptsBlindedCommunityMessageRequests
            is RecipientData.Generic -> data.acceptsBlindedCommunityMessageRequests
            is RecipientData.Community,
            is RecipientData.LegacyGroup,
            is RecipientData.Contact,
            is RecipientData.Self,
            is RecipientData.Group -> false
        }
}
