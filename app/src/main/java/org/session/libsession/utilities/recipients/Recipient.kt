package org.session.libsession.utilities.recipients

import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.avatars.ContactPhoto
import org.session.libsession.avatars.ProfileContactPhoto
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.truncateIdForDisplay
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.database.model.NotifyType
import java.time.ZonedDateTime

data class Recipient(
    val basic: BasicRecipient,
    val mutedUntil: ZonedDateTime?,
    val autoDownloadAttachments: Boolean?,
    @get:NotifyType
    val notifyType: Int,
    val acceptsCommunityMessageRequests: Boolean,
    val notificationChannel: String? = null,
) {
    val isLocalNumber: Boolean get() = basic.isLocalNumber
    val address: Address get() = basic.address
    val avatar: RecipientAvatar? get() = basic.avatar

    val isGroupOrCommunityRecipient: Boolean get() = basic.isGroupOrCommunityRecipient
    val isCommunityRecipient: Boolean get() = basic.isCommunityRecipient
    val isCommunityInboxRecipient: Boolean get() = basic.isCommunityInboxRecipient
    val isCommunityOutboxRecipient: Boolean get() = basic.isCommunityOutboxRecipient
    val isGroupV2Recipient: Boolean get() = basic.isGroupV2Recipient
    val isLegacyGroupRecipient: Boolean get() = basic.isLegacyGroupRecipient
    val isContactRecipient: Boolean get() = basic.isContactRecipient
    val is1on1: Boolean get() = basic.is1on1
    val isGroupRecipient: Boolean get() = basic.isGroupRecipient

    val displayName: String get() = basic.displayName
    val expiryMode: ExpiryMode get() = when (basic) {
        is BasicRecipient.Self -> basic.expiryMode
        is BasicRecipient.Contact -> basic.expiryMode
        is BasicRecipient.Group -> basic.expiryMode
        else -> ExpiryMode.NONE
    }

    val approved: Boolean get() = when (basic) {
        is BasicRecipient.Contact -> basic.approved
        is BasicRecipient.Group -> basic.approved
        else -> true
    }

    val approvedMe: Boolean get() = (basic as? BasicRecipient.Contact)?.approvedMe ?: true
    val blocked: Boolean get() = when (basic) {
        is BasicRecipient.Generic -> basic.blocked
        is BasicRecipient.Contact -> basic.blocked
        else -> false
    }

    @JvmOverloads
    fun isMuted(now: ZonedDateTime = ZonedDateTime.now()): Boolean {
        return mutedUntil?.isAfter(now) == true
    }

    val showCallMenu: Boolean
        get() = !isGroupOrCommunityRecipient && approvedMe && approved

    val mutedUntilMills: Long?
        get() = mutedUntil?.toInstant()?.toEpochMilli()

    @Deprecated("use avatar instead")
    //TODO: Not working
    val contactPhoto: ContactPhoto?
        get() = when (val a = avatar) {
            is RecipientAvatar.EncryptedRemotePic -> ProfileContactPhoto(address, a.url)
            is RecipientAvatar.Inline -> null
            else -> null
        }

    @Deprecated("Use `avatar` property instead", ReplaceWith("avatar"))
    val profileAvatar: String?
        get() = (avatar as? RecipientAvatar.EncryptedRemotePic)?.url

    @Deprecated("Use `avatar` property instead", ReplaceWith("avatar?.toUserPic()"))
    val profileKey: ByteArray?
        get() = (avatar as? RecipientAvatar.EncryptedRemotePic)?.key?.data

    companion object {
        fun empty(address: Address): Recipient {
            return Recipient(
                basic = BasicRecipient.Generic(address),
                mutedUntil = null,
                autoDownloadAttachments = true,
                notifyType = RecipientDatabase.NOTIFY_TYPE_ALL,
                acceptsCommunityMessageRequests = false,
            )
        }
    }
}

sealed interface BasicRecipient {
    val address: Address
    val isLocalNumber: Boolean
    val displayName: String
    val avatar: RecipientAvatar?

    /**
     * A recipient that is backed by the config system.
     */
    sealed interface ConfigBasedRecipient : BasicRecipient

    data class Generic(
        override val address: Address,
        override val displayName: String = "",
        override val avatar: RecipientAvatar? = null,
        override val isLocalNumber: Boolean = false,
        val blocked: Boolean = false,
    ) : BasicRecipient

    /**
     * Yourself.
     */
    data class Self(
        val name: String,
        override val address: Address,
        override val avatar: RecipientAvatar.EncryptedRemotePic?,
        val expiryMode: ExpiryMode,
        val acceptsCommunityMessageRequests: Boolean,
    ) : ConfigBasedRecipient {
        override val displayName: String
            get() = name

        override val isLocalNumber: Boolean
            get() = true
    }

    /**
     * A recipient that is your **real** contact.
     */
    data class Contact(
        override val address: Address,
        val name: String,
        val nickname: String?,
        override val avatar: RecipientAvatar.EncryptedRemotePic?,
        val approved: Boolean,
        val approvedMe: Boolean,
        val blocked: Boolean,
        val expiryMode: ExpiryMode
    ) : ConfigBasedRecipient {
        override val displayName: String
            get() = nickname?.takeIf { it.isNotBlank() } ?: name

        override val isLocalNumber: Boolean
            get() = false
    }

    /**
     * A recipient that is a groupv2.
     */
    data class Group(
        override val address: Address,
        val name: String,
        override val avatar: RecipientAvatar.EncryptedRemotePic?,
        val expiryMode: ExpiryMode,
        val approved: Boolean,
    ) : ConfigBasedRecipient {
        override val displayName: String
            get() = name

        override val isLocalNumber: Boolean
            get() = false
    }
}

val BasicRecipient.isGroupOrCommunityRecipient: Boolean get() = address.isGroupOrCommunity
val BasicRecipient.isCommunityRecipient: Boolean get() = address.isCommunity
val BasicRecipient.isCommunityInboxRecipient: Boolean get() = address.isCommunityInbox
val BasicRecipient.isCommunityOutboxRecipient: Boolean get() = address.isCommunityOutbox
val BasicRecipient.isGroupV2Recipient: Boolean get() = address.isGroupV2
val BasicRecipient.isLegacyGroupRecipient: Boolean get() = address.isLegacyGroup
val BasicRecipient.isContactRecipient: Boolean get() = address.isContact
val BasicRecipient.is1on1: Boolean get() = !isLocalNumber && address.isContact
val BasicRecipient.isGroupRecipient: Boolean get() = address.isGroup


sealed interface RecipientAvatar {
    data class EncryptedRemotePic(val url: String, val key: Bytes) : RecipientAvatar
    data class Inline(val bytes: Bytes) : RecipientAvatar

    companion object {
        fun UserPic.toRecipientAvatar(): RecipientAvatar.EncryptedRemotePic? {
            return when {
                url.isBlank() -> null
                else ->  EncryptedRemotePic(
                    url = url,
                    key = key
                )
            }
        }

        fun from(url: String, bytes: ByteArray?): RecipientAvatar? {
            return if (url.isNotBlank() && bytes != null && bytes.isNotEmpty()) {
                EncryptedRemotePic(url, Bytes(bytes))
            } else {
                null
            }
        }

        fun fromBytes(bytes: ByteArray?): RecipientAvatar? {
            return if (bytes == null || bytes.isEmpty()) {
                null
            } else {
                Inline(Bytes(bytes))
            }
        }
    }
}

/**
 * Represents local database data for a recipient.
 */
class RecipientSettings(
    val blocked: Boolean,
    val approved: Boolean,
    val approvedMe: Boolean,
    val muteUntil: Long,
    val notifyType: Int,
    val autoDownloadAttachments: Boolean?,
    val expireMessages: Int,
    val profileKey: ByteArray?,
    val systemDisplayName: String?,
    val profileName: String?,
    val profileAvatar: String?,
    val blocksCommunityMessagesRequests: Boolean
) {
    val profilePic: UserPic? get() =
        if (!profileAvatar.isNullOrBlank() && profileKey != null) {
            UserPic(profileAvatar, profileKey)
        } else {
            null
        }
}

fun RecipientAvatar.toUserPic(): UserPic? {
    return when (this) {
        is RecipientAvatar.EncryptedRemotePic -> UserPic(url, key)
        is RecipientAvatar.Inline -> null
    }
}

inline fun Recipient?.displayNameOrFallback(fallbackName: () -> String? = { null }, address: String): String {
    return this?.displayName
        ?: fallbackName()?.takeIf { it.isNotBlank() }
        ?: truncateIdForDisplay(address)
}