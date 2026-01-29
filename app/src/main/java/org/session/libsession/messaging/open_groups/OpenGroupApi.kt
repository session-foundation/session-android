package org.session.libsession.messaging.open_groups

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.databind.annotation.JsonNaming
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.Hash
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.model.OnionResponse
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64.encodeBytes
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

object OpenGroupApi {
    const val legacyServerIP = "116.203.70.33"

    sealed class Error(message: String) : Exception(message) {
        object Generic : Error("An error occurred.")
        object ParsingFailed : Error("Invalid response.")
        object DecryptionFailed : Error("Couldn't decrypt response.")
        object SigningFailed : Error("Couldn't sign message.")
        object InvalidURL : Error("Invalid URL.")
        object NoPublicKey : Error("Couldn't find server public key.")
        object NoEd25519KeyPair : Error("Couldn't find ed25519 key pair.")
    }

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
        val created: Double = 0.0,
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
    )

    @JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy::class)
    @Serializable
    data class DirectMessage(
        val id: Long = 0,
        val sender: String = "",
        val recipient: String = "",
        @SerialName("posted_at")
        val postedAt: Long = 0,
        @SerialName("expires_at")
        val expiresAt: Long = 0,
        val message: String = "",
    )

    @JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy::class)
    @Serializable
    data class Message(
        val id : Long = 0,
        @SerialName("session_id")
        val sessionId: String = "",
        val posted: Double = 0.0,
        val edited: Double = 0.0,
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

    @Serializable
    data class DeleteReactionResponse(
        @SerialName("seqno")
        val seqNo: Long,
        val removed: Boolean
    )

    data class DeleteAllReactionsResponse(
        val seqNo: Long,
        val removed: Boolean
    )

    data class PendingReaction(
        val server: String,
        val room: String,
        val messageId: Long,
        val emoji: String,
        val add: Boolean,
        var seqNo: Long? = null
    )

    data class MessageDeletion(
        @JsonProperty("id")
        val id: Long = 0,
        @JsonProperty("deleted_message_id")
        val deletedMessageServerID: Long = 0
    ) {

        companion object {
            val empty = MessageDeletion()
        }
    }
}