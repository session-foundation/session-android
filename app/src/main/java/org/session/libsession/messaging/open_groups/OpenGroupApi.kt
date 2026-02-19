package org.session.libsession.messaging.open_groups

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.session.libsession.utilities.serializable.InstantAsSecondDoubleSerializer
import org.session.libsignal.utilities.ByteArraySlice
import java.time.Instant

object OpenGroupApi {
    const val legacyServerIP = "116.203.70.33"

    data class DefaultGroup(val serverUrl: String, val publicKey: String, val id: String, val name: String, val image: ByteArraySlice?) {

        val joinURL: String get() = "$serverUrl/$id?public_key=$publicKey"
    }

    @Serializable
    data class RoomInfoDetails(
        val token: String = "",
        val name: String = "",
        val description: String = "",
        @SerialName("info_updates")
        val infoUpdates: Int = 0,
        @SerialName("message_sequence")
        val messageSequence: Long = 0,
        @Serializable(with = InstantAsSecondDoubleSerializer::class)
        val created: Instant? = null,
        @SerialName("active_users")
        val activeUsers: Int = 0,
        @SerialName("active_users_cutoff")
        val activeUsersCutoff: Int = 0,
        @SerialName("image_id")
        val imageId: String? = null,
        @SerialName("pinned_messages")
        val pinnedMessages: List<PinnedMessage> = emptyList(),
        val admin: Boolean = false,
        @SerialName("global_admin")
        val globalAdmin: Boolean = false,
        val admins: List<String> = emptyList(),
        @SerialName("hidden_admins")
        val hiddenAdmins: List<String> = emptyList(),
        val moderator: Boolean = false,
        @SerialName("global_moderator")
        val globalModerator: Boolean = false,
        val moderators: List<String> = emptyList(),
        @SerialName("hidden_moderators")
        val hiddenModerators: List<String> = emptyList(),
        val read: Boolean = false,
        @SerialName("default_read")
        val defaultRead: Boolean = false,
        @SerialName("default_accessible")
        val defaultAccessible: Boolean = false,
        val write: Boolean = false,
        @SerialName("default_write")
        val defaultWrite: Boolean = false,
        val upload: Boolean = false,
        @SerialName("default_upload")
        val defaultUpload: Boolean = false,
    )

    @Serializable
    data class PinnedMessage(
        val id: Long = 0,
        @SerialName("pinned_at")
        val pinnedAt: Long = 0,
        @SerialName("pinned_by")
        val pinnedBy: String = ""
    )

    @Serializable
    data class Capabilities(
        val capabilities: List<String> = emptyList(),
        val missing: List<String> = emptyList()
    )

    enum class Capability {
        SOGS, BLIND, REACTIONS
    }

    @Serializable
    data class RoomInfo(
        val token: String = "",
        @SerialName("active_users")
        val activeUsers: Int = 0,
        val admin: Boolean = false,
        @SerialName("global_admin")
        val globalAdmin: Boolean = false,
        val moderator: Boolean = false,
        @SerialName("global_moderator")
        val globalModerator: Boolean = false,
        val read: Boolean = false,
        @SerialName("default_read")
        val defaultRead: Boolean = false,
        @SerialName("default_accessible")
        val defaultAccessible: Boolean = false,
        val write: Boolean = false,
        @SerialName("default_write")
        val defaultWrite: Boolean = false,
        val upload: Boolean = false,
        @SerialName("default_upload")
        val defaultUpload: Boolean = false,
        val details: RoomInfoDetails = RoomInfoDetails()
    ) {
        constructor(details: RoomInfoDetails): this(
            token = details.token,
            activeUsers = details.activeUsers,
            admin = details.admin,
            globalAdmin = details.globalAdmin,
            moderator = details.moderator,
            globalModerator = details.globalModerator,
            read = details.read,
            defaultRead = details.defaultRead,
            defaultAccessible = details.defaultAccessible,
            write = details.write,
            defaultWrite = details.defaultWrite,
            upload = details.upload,
            defaultUpload = details.defaultUpload,
            details = details
        )
    }

    @Serializable
    data class DirectMessage(
        val id: Long = 0,
        val sender: String = "",
        val recipient: String = "",
        @SerialName("posted_at")
        @Serializable(with = InstantAsSecondDoubleSerializer::class)
        val postedAt: Instant? = null,
        @SerialName("expires_at")
        @Serializable(with = InstantAsSecondDoubleSerializer::class)
        val expiresAt: Instant? = null,
        val message: String = "",
    )

    @Serializable
    data class Message(
        val id : Long = 0,
        @SerialName("session_id")
        val sessionId: String = "",
        @Serializable(with = InstantAsSecondDoubleSerializer::class)
        val posted: Instant? = null,
        @Serializable(with = InstantAsSecondDoubleSerializer::class)
        val edited: Instant? = null,
        val seqno: Long = 0,
        val deleted: Boolean = false,
        val whisper: Boolean = false,
        @SerialName("whisper_mods")
        val whisperMods: String = "",

        @SerialName("whisper_to")
        val whisperTo: String = "",
        val data: String? = null,
        val signature: String? = null,
        val reactions: Map<String, Reaction>? = null,
    )

    @Serializable
    data class Reaction(
        val count: Long = 0,
        val reactors: List<String> = emptyList(),
        val you: Boolean = false,
        val index: Long = 0
    )

    @Serializable
    data class AddReactionResponse(
        @SerialName("seqno")
        val seqNo: Long,
        val added: Boolean
    )

}