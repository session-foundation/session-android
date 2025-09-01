package org.session.libsession.utilities.recipients

import network.loki.messenger.libsession_util.ConfigBase.Companion.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.GroupMember
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.messaging.open_groups.GroupMemberRole
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.AccountId
import java.time.Instant

/**
 * Represents different kind of data associated with different types of recipients.
 */
sealed interface RecipientData {
    val avatar: RemoteFile?
    val priority: Long
    val profileUpdatedAt: Instant?

    val proStatus: ProStatus

    // Marker interface to distinguish between config-based and other recipient data.
    sealed interface ConfigBased

    /**
     * Represents a group-like recipient, which can be a group or community.
     */
    sealed interface GroupLike : RecipientData {
        // The first member of this group, for profile picture assembly purposes.
        val firstMember: Recipient?

        // The second member of this group, for profile picture assembly purposes.
        val secondMember: Recipient?

        /**
         * Checks if the given user is an admin of this group or community.
         */
        fun hasAdmin(user: AccountId): Boolean

        /**
         * Determines if the admin crown should be shown for the given user.
         */
        fun shouldShowAdminCrown(user: AccountId): Boolean
    }

    data class Generic(
        val displayName: String = "",
        override val avatar: RemoteFile? = null,
        override val priority: Long = PRIORITY_VISIBLE,
        override val proStatus: ProStatus = ProStatus.None,
        val acceptsBlindedCommunityMessageRequests: Boolean = false,
        override val profileUpdatedAt: Instant? = null,
    ) : RecipientData

    data class BlindedContact(
        val displayName: String,
        override val avatar: RemoteFile.Encrypted?,
        override val priority: Long,
        override val proStatus: ProStatus,
        val acceptsBlindedCommunityMessageRequests: Boolean,
        override val profileUpdatedAt: Instant?
    ) : ConfigBased, RecipientData

    data class Community(
        val openGroup: OpenGroup,
        override val priority: Long,
        val roles: Map<AccountId, GroupMemberRole>,
    ) : RecipientData, GroupLike {
        override val avatar: RemoteFile?
            get() = openGroup.imageId?.let { RemoteFile.Community(openGroup.server, openGroup.room, it) }

        override val firstMember: Recipient?
            get() = null

        override val secondMember: Recipient?
            get() = null

        override val profileUpdatedAt: Instant?
            get() = null

        override fun hasAdmin(user: AccountId): Boolean {
            return roles[user]?.canModerate == true
        }

        override fun shouldShowAdminCrown(user: AccountId): Boolean {
            return roles[user]?.shouldShowAdminCrown == true
        }

        override val proStatus: ProStatus
            get() = ProStatus.None
    }

    /**
     * Yourself.
     */
    data class Self(
        val name: String,
        override val avatar: RemoteFile.Encrypted?,
        val expiryMode: ExpiryMode,
        override val priority: Long,
        override val proStatus: ProStatus,
        override val profileUpdatedAt: Instant?
    ) : ConfigBased, RecipientData

    /**
     * A recipient that was saved in your contact config.
     */
    data class Contact(
        val name: String,
        val nickname: String?,
        override val avatar: RemoteFile.Encrypted?,
        val approved: Boolean,
        val approvedMe: Boolean,
        val blocked: Boolean,
        val expiryMode: ExpiryMode,
        override val priority: Long,
        override val proStatus: ProStatus,
        override val profileUpdatedAt: Instant?,
    ) : ConfigBased, RecipientData {
        val displayName: String
            get() = nickname?.takeIf { it.isNotBlank() } ?: name
    }

    data class GroupMemberInfo(
        val address: Address.Standard,
        val name: String,
        val profilePic: UserPic?,
        val isAdmin: Boolean
    ) {
        constructor(member: GroupMember) : this(
            name = member.name,
            profilePic = member.profilePic(),
            address = Address.Standard(AccountId(member.accountId())),
            isAdmin = member.admin
        )
    }

    /**
     * Group data fetched from the config. It's named as "partial" because it does not include
     * all the information we need to resemble a full group recipient, hence not implementing the
     * [RecipientData] interface.
     */
    data class PartialGroup(
        val name: String,
        val avatar: RemoteFile.Encrypted?,
        val expiryMode: ExpiryMode,
        val approved: Boolean,
        val priority: Long,
        val isAdmin: Boolean,
        val kicked: Boolean,
        val destroyed: Boolean,
        val proStatus: ProStatus,
        val members: List<GroupMemberInfo>,
    ) : ConfigBased

    /**
     * Full group data that includes additional information that may not be present in the config.
     */
    data class Group(
        val partial: PartialGroup,
        override val firstMember: Recipient, // Used primarily to assemble the profile picture for the group.
        override val secondMember: Recipient?, // Used primarily to assemble the profile picture for the group.
    ) : RecipientData, GroupLike {
        override val avatar: RemoteFile?
            get() = partial.avatar

        override val priority: Long
            get() = partial.priority

        override val proStatus: ProStatus
            get() = partial.proStatus

        override val profileUpdatedAt: Instant?
            get() = null

        override fun hasAdmin(user: AccountId): Boolean {
            return partial.members.any { it.address.accountId == user && it.isAdmin }
        }

        override fun shouldShowAdminCrown(user: AccountId): Boolean {
            return hasAdmin(user)
        }
    }

    data class LegacyGroup(
        val name: String,
        override val priority: Long,
        val members: Map<AccountId, GroupMemberRole>,
        val isCurrentUserAdmin: Boolean,
        override val firstMember: Recipient, // Used primarily to assemble the profile picture for the group.
        override val secondMember: Recipient?, // Used primarily to assemble the profile picture for the group.
    ) : RecipientData, GroupLike {
        override val avatar: RemoteFile?
            get() = null

        override val proStatus: ProStatus
            get() = ProStatus.None

        override fun hasAdmin(user: AccountId): Boolean {
            return members[user]?.canModerate == true
        }

        override fun shouldShowAdminCrown(user: AccountId): Boolean {
            return members[user]?.shouldShowAdminCrown == true
        }

        override val profileUpdatedAt: Instant?
            get() = null
    }
}