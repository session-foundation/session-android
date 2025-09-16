package org.session.libsession.messaging.open_groups

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.fasterxml.jackson.databind.type.TypeFactory
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.Hash
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.map
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.snode.OnionResponse
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.utilities.asyncPromise
import org.session.libsession.snode.utilities.await
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64.encodeBytes
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.HTTP
import org.session.libsignal.utilities.HTTP.Verb.DELETE
import org.session.libsignal.utilities.HTTP.Verb.GET
import org.session.libsignal.utilities.HTTP.Verb.POST
import org.session.libsignal.utilities.HTTP.Verb.PUT
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set

object OpenGroupApi {
    val defaultRooms = MutableSharedFlow<List<DefaultGroup>>(replay = 1)
    const val defaultServerPublicKey = "a03c383cf63c3c4efe67acc52112a6dd734b3a946b9545f488aaa93da7991238"
    const val legacyServerIP = "116.203.70.33"
    const val legacyDefaultServer = "http://116.203.70.33" // TODO: migrate all references to use new value

    /** For migration purposes only, don't use this value in joining groups */
    const val httpDefaultServer = "http://open.getsession.org"

    const val defaultServer = "https://open.getsession.org"

    val pendingReactions = mutableListOf<PendingReaction>()

    sealed class Error(message: String) : Exception(message) {
        object Generic : Error("An error occurred.")
        object ParsingFailed : Error("Invalid response.")
        object DecryptionFailed : Error("Couldn't decrypt response.")
        object SigningFailed : Error("Couldn't sign message.")
        object InvalidURL : Error("Invalid URL.")
        object NoPublicKey : Error("Couldn't find server public key.")
        object NoEd25519KeyPair : Error("Couldn't find ed25519 key pair.")
    }

    data class DefaultGroup(val id: String, val name: String, val image: ByteArraySlice?) {

        val joinURL: String get() = "$defaultServer/$id?public_key=$defaultServerPublicKey"
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

    data class BatchRequestInfo<T>(
        val request: BatchRequest,
        val endpoint: Endpoint,
        val queryParameters: Map<String, String> = mapOf(),
        val responseType: TypeReference<T>?
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class BatchRequest(
        val method: HTTP.Verb,
        val path: String,
        val headers: Map<String, String> = emptyMap(),
        val json: Map<String, Any>? = null,
        val b64: String? = null,
        val bytes: ByteArray? = null,
    )

    data class BatchResponse<T>(
        val endpoint: Endpoint,
        val code: Int,
        val headers: Map<String, String>,
        val body: T?
    )

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
    data class DirectMessage(
        val id: Long = 0,
        val sender: String = "",
        val recipient: String = "",
        val postedAt: Long = 0,
        val expiresAt: Long = 0,
        val message: String = "",
    )

    @JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy::class)
    data class Message(
        val id : Long = 0,
        val sessionId: String = "",
        val posted: Double = 0.0,
        val edited: Long = 0,
        val seqno: Long = 0,
        val deleted: Boolean = false,
        val whisper: Boolean = false,
        val whisperMods: String = "",
        val whisperTo: String = "",
        val data: String? = null,
        val signature: String? = null,
        val reactions: Map<String, Reaction>? = null,
    )

    data class Reaction(
        val count: Long = 0,
        val reactors: List<String> = emptyList(),
        val you: Boolean = false,
        val index: Long = 0
    )

    data class AddReactionResponse(
        val seqNo: Long,
        val added: Boolean
    )

    data class DeleteReactionResponse(
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

    @JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy::class)
    data class SendMessageRequest(
        val data: String? = null,
        val signature: String? = null,
        val whisperTo: List<String>? = null,
        val whisperMods: Boolean? = null,
        val files: List<String>? = null
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

    data class Request(
        val verb: HTTP.Verb,
        val room: String?,
        val server: String,
        val endpoint: Endpoint,
        val queryParameters: Map<String, String> = mapOf(),
        val parameters: Any? = null,
        val headers: Map<String, String> = mapOf(),
        val body: ByteArray? = null,
        /**
         * Always `true` under normal circumstances. You might want to disable
         * this when running over Lokinet.
         */
        val useOnionRouting: Boolean = true
    )

    private fun createBody(body: ByteArray?, parameters: Any?): RequestBody? {
        if (body != null) return RequestBody.create("application/octet-stream".toMediaType(), body)
        if (parameters == null) return null
        val parametersAsJSON = JsonUtil.toJson(parameters)
        return RequestBody.create("application/json".toMediaType(), parametersAsJSON)
    }

    private fun getResponseBody(
        request: Request,
        signRequest: Boolean = true,
        serverPubKeyHex: String? = null
    ): Promise<ByteArraySlice, Exception> {
        return send(request, signRequest = signRequest, serverPubKeyHex = serverPubKeyHex).map { response ->
            response.body ?: throw Error.ParsingFailed
        }
    }

    private fun getResponseBodyJson(request: Request, signRequest: Boolean = true): Promise<Map<*, *>, Exception> {
        return send(request, signRequest = signRequest).map {
            JsonUtil.fromJson(it.body, Map::class.java)
        }
    }

    suspend fun getOrFetchServerCapabilities(server: String): List<String> {
        val storage = MessagingModuleConfiguration.shared.storage
        val caps = storage.getServerCapabilities(server)

        if (caps != null) {
            return caps
        }

        val fetched = getCapabilities(server).await()
        storage.setServerCapabilities(server, fetched.capabilities)
        return fetched.capabilities
    }

    private fun send(request: Request, signRequest: Boolean, serverPubKeyHex: String? = null): Promise<OnionResponse, Exception> {
        request.server.toHttpUrlOrNull() ?: return Promise.ofFail(Error.InvalidURL)
        val urlBuilder = StringBuilder("${request.server}/${request.endpoint.value}")
        if (request.verb == GET && request.queryParameters.isNotEmpty()) {
            urlBuilder.append("?")
            for ((key, value) in request.queryParameters) {
                urlBuilder.append("$key=$value")
            }
        }

        suspend fun execute(): OnionResponse {
            val serverPublicKey = serverPubKeyHex
                ?: MessagingModuleConfiguration.shared.storage.getOpenGroupPublicKey(request.server)
                ?: throw Error.NoPublicKey
            val urlRequest = urlBuilder.toString()

            val headers = if (signRequest) {
                val serverCapabilities = getOrFetchServerCapabilities(request.server)

                val ed25519KeyPair = MessagingModuleConfiguration.shared.storage.getUserED25519KeyPair()
                    ?: throw Error.NoEd25519KeyPair

                val headers = request.headers.toMutableMap()
                val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val timestamp = TimeUnit.MILLISECONDS.toSeconds(SnodeAPI.nowWithOffset)
                val bodyHash = if (request.parameters != null) {
                    val parameterBytes = JsonUtil.toJson(request.parameters).toByteArray()
                    Hash.hash64(parameterBytes)
                } else if (request.body != null) {
                    Hash.hash64(request.body)
                } else {
                    byteArrayOf()
                }

                val messageBytes = Hex.fromStringCondensed(serverPublicKey)
                    .plus(nonce)
                    .plus("$timestamp".toByteArray(Charsets.US_ASCII))
                    .plus(request.verb.rawValue.toByteArray())
                    .plus("/${request.endpoint.value}".toByteArray())
                    .plus(bodyHash)

                val signature: ByteArray
                val pubKey: String

                if (serverCapabilities.isEmpty() || serverCapabilities.contains(Capability.BLIND.name.lowercase())) {
                    pubKey = AccountId(
                        IdPrefix.BLINDED,
                        BlindKeyAPI.blind15KeyPair(
                            ed25519SecretKey = ed25519KeyPair.secretKey.data,
                            serverPubKey = Hex.fromStringCondensed(serverPublicKey)
                        ).pubKey.data
                    ).hexString

                    try {
                        signature = BlindKeyAPI.blind15Sign(
                            ed25519SecretKey = ed25519KeyPair.secretKey.data,
                            serverPubKey = serverPublicKey,
                            message = messageBytes
                        )
                    } catch (e: Exception) {
                        throw Error.SigningFailed
                    }
                } else {
                    pubKey = AccountId(
                        IdPrefix.UN_BLINDED,
                        ed25519KeyPair.pubKey.data
                    ).hexString

                    signature = ED25519.sign(
                        ed25519PrivateKey = ed25519KeyPair.secretKey.data,
                        message = messageBytes
                    )
                }
                headers["X-SOGS-Nonce"] = encodeBytes(nonce)
                headers["X-SOGS-Timestamp"] = "$timestamp"
                headers["X-SOGS-Pubkey"] = pubKey
                headers["X-SOGS-Signature"] = encodeBytes(signature)
                headers
            } else {
                request.headers
            }

            val requestBuilder = okhttp3.Request.Builder()
                .url(urlRequest)
                .headers(headers.toHeaders())
            when (request.verb) {
                GET -> requestBuilder.get()
                PUT -> requestBuilder.put(createBody(request.body, request.parameters)!!)
                POST -> requestBuilder.post(createBody(request.body, request.parameters)!!)
                DELETE -> requestBuilder.delete(createBody(request.body, request.parameters))
            }
            if (!request.room.isNullOrEmpty()) {
                requestBuilder.header("Room", request.room)
            }
            return if (request.useOnionRouting) {
                OnionRequestAPI.sendOnionRequest(requestBuilder.build(), request.server, serverPublicKey).fail { e ->
                    when (e) {
                        // No need for the stack trace for HTTP errors
                        is HTTP.HTTPRequestFailedException -> Log.e("SOGS", "Failed onion request: ${e.message}")
                        else -> Log.e("SOGS", "Failed onion request", e)
                    }
                }.await()
            } else {
                throw IllegalStateException("It's currently not allowed to send non onion routed requests.")
            }
        }

        return GlobalScope.asyncPromise(block=::execute)
    }

    fun downloadOpenGroupProfilePicture(
        server: String,
        roomID: String,
        imageId: String,
        signRequest: Boolean = true,
    ): Promise<ByteArraySlice, Exception> {
        val request = Request(
            verb = GET,
            room = roomID,
            server = server,
            endpoint = Endpoint.RoomFileIndividual(roomID, imageId)
        )
        return getResponseBody(request, signRequest = signRequest)
    }

    // region Upload/Download
    fun upload(file: ByteArray, room: String, server: String): Promise<String, Exception> {
        val request = Request(
            verb = POST,
            room = room,
            server = server,
            endpoint = Endpoint.RoomFile(room),
            body = file,
            headers = mapOf(
                "Content-Disposition" to "attachment",
                "Content-Type" to "application/octet-stream"
            )
        )
        return getResponseBodyJson(request, signRequest = true).map { json ->
            json["id"]?.toString() ?: throw Error.ParsingFailed
        }
    }

    fun download(fileId: String, room: String, server: String): Promise<ByteArraySlice, Exception> {
        val request = Request(
            verb = GET,
            room = room,
            server = server,
            endpoint = Endpoint.RoomFileIndividual(room, fileId)
        )
        return getResponseBody(request, signRequest = true)
    }
    // endregion

    // region Sending
    fun sendMessage(
        message: OpenGroupMessage,
        room: String,
        server: String,
        whisperTo: List<String>? = null,
        whisperMods: Boolean? = null,
        fileIds: List<String>? = null
    ): Promise<OpenGroupMessage, Exception> {
        val signedMessage = message.sign(server) ?: return Promise.ofFail(Error.SigningFailed)
        val parameters = signedMessage.toJSON().toMutableMap()

        // add file IDs if there are any (from attachments)
        if (!fileIds.isNullOrEmpty()) {
            parameters += "files" to fileIds
        }

        val request = Request(
            verb = POST,
            room = room,
            server = server,
            endpoint = Endpoint.RoomMessage(room),
            parameters = parameters
        )
        return getResponseBodyJson(request, signRequest = true).map { json ->
            @Suppress("UNCHECKED_CAST") val rawMessage = json as? Map<String, Any>
                ?: throw Error.ParsingFailed
            val result = OpenGroupMessage.fromJSON(rawMessage) ?: throw Error.ParsingFailed
            val storage = MessagingModuleConfiguration.shared.storage
            storage.addReceivedMessageTimestamp(result.sentTimestamp)
            result
        }
    }
    // endregion

    fun addReaction(room: String, server: String, messageId: Long, emoji: String): Promise<AddReactionResponse, Exception> {
        val request = Request(
            verb = PUT,
            room = room,
            server = server,
            endpoint = Endpoint.Reaction(room, messageId, emoji),
            parameters = emptyMap<String, String>()
        )
        val pendingReaction = PendingReaction(server, room, messageId, emoji, true)
        return getResponseBody(request, signRequest = true).map { response ->
            JsonUtil.fromJson(response, AddReactionResponse::class.java).also {
                val index = pendingReactions.indexOf(pendingReaction)
                pendingReactions[index].seqNo = it.seqNo
            }
        }
    }

    fun deleteReaction(room: String, server: String, messageId: Long, emoji: String): Promise<DeleteReactionResponse, Exception> {
        val request = Request(
            verb = DELETE,
            room = room,
            server = server,
            endpoint = Endpoint.Reaction(room, messageId, emoji)
        )
        val pendingReaction = PendingReaction(server, room, messageId, emoji, true)
        return getResponseBody(request, signRequest = true).map { response ->
            JsonUtil.fromJson(response, DeleteReactionResponse::class.java).also {
                val index = pendingReactions.indexOf(pendingReaction)
                pendingReactions[index].seqNo = it.seqNo
            }
        }
    }

    fun deleteAllReactions(room: String, server: String, messageId: Long, emoji: String): Promise<DeleteAllReactionsResponse, Exception> {
        val request = Request(
            verb = DELETE,
            room = room,
            server = server,
            endpoint = Endpoint.ReactionDelete(room, messageId, emoji)
        )
        return getResponseBody(request, signRequest = true).map { response ->
            JsonUtil.fromJson(response, DeleteAllReactionsResponse::class.java)
        }
    }
    // endregion

    // region Message Deletion
    @JvmStatic
    fun deleteMessage(serverID: Long, room: String, server: String): Promise<Unit, Exception> {
        val request = Request(verb = DELETE, room = room, server = server, endpoint = Endpoint.RoomMessageIndividual(room, serverID))
        return send(request, signRequest = true).map {
            Log.d("Loki", "Message deletion successful.")
        }
    }

    fun getDeletedMessages(
        room: String,
        server: String
    ): Promise<List<MessageDeletion>, Exception> {
        val storage = MessagingModuleConfiguration.shared.storage
        val queryParameters = mutableMapOf<String, String>()
        storage.getLastDeletionServerID(room, server)?.let { last ->
            queryParameters["from_server_id"] = last.toString()
        }
        val request = Request(
            verb = GET,
            room = room,
            server = server,
            endpoint = Endpoint.RoomDeleteMessages(room, storage.getUserPublicKey() ?: ""),
            queryParameters = queryParameters
        )
        return getResponseBody(request, signRequest = true).map { response ->
            val json = JsonUtil.fromJson(response, Map::class.java)
            val type = TypeFactory.defaultInstance()
                .constructCollectionType(List::class.java, MessageDeletion::class.java)
            val idsAsString = JsonUtil.toJson(json["ids"])
            val serverIDs = JsonUtil.fromJson<List<MessageDeletion>>(idsAsString, type)
                ?: throw Error.ParsingFailed
            val lastMessageServerId = storage.getLastDeletionServerID(room, server) ?: 0
            val serverID = serverIDs.maxByOrNull { it.id } ?: MessageDeletion.empty
            if (serverID.id > lastMessageServerId) {
                storage.setLastDeletionServerID(room, server, serverID.id)
            }
            serverIDs
        }
    }
    // endregion

    // region Moderation
    @JvmStatic
    fun ban(publicKey: String, room: String, server: String): Promise<Unit, Exception> {
        val parameters =  mapOf("rooms" to listOf(room))
        val request = Request(
            verb = POST,
            room = room,
            server = server,
            endpoint = Endpoint.UserBan(publicKey),
            parameters = parameters
        )
        return send(request, signRequest = true).map {
            Log.d("Loki", "Banned user: $publicKey from: $server.$room.")
        }
    }

    fun banAndDeleteAll(publicKey: String, room: String, server: String): Promise<Unit, Exception> {

        val requests = mutableListOf<BatchRequestInfo<*>>(
            // Ban request
            BatchRequestInfo(
                request = BatchRequest(
                    method = POST,
                    path = "/user/$publicKey/ban",
                    json = mapOf("rooms" to listOf(room))
                ),
                endpoint = Endpoint.UserBan(publicKey),
                responseType = object: TypeReference<Any>(){}
            ),
            // Delete request
            BatchRequestInfo(
                request = BatchRequest(DELETE, "/room/$room/all/$publicKey"),
                endpoint = Endpoint.RoomDeleteMessages(room, publicKey),
                responseType = object: TypeReference<Any>(){}
            )
        )
        return sequentialBatch(server, requests).map {
            Log.d("Loki", "Banned user: $publicKey from: $server.$room.")
        }
    }

    fun unban(publicKey: String, room: String, server: String): Promise<Unit, Exception> {
        val request = Request(verb = DELETE, room = room, server = server, endpoint = Endpoint.UserUnban(publicKey))
        return send(request, signRequest = true).map {
            Log.d("Loki", "Unbanned user: $publicKey from: $server.$room")
        }
    }
    // endregion

    // region General

    fun parallelBatch(
        server: String,
        requests: MutableList<BatchRequestInfo<*>>
    ): Promise<List<BatchResponse<*>>, Exception> {
        val request = Request(
            verb = POST,
            room = null,
            server = server,
            endpoint = Endpoint.Batch,
            parameters = requests.map { it.request }
        )
        return getBatchResponseJson(request, requests)
    }

    private fun sequentialBatch(
        server: String,
        requests: MutableList<BatchRequestInfo<*>>
    ): Promise<List<BatchResponse<*>>, Exception> {
        val request = Request(
            verb = POST,
            room = null,
            server = server,
            endpoint = Endpoint.Sequence,
            parameters = requests.map { it.request }
        )
        return getBatchResponseJson(request, requests)
    }

    private fun getBatchResponseJson(
        request: Request,
        requests: MutableList<BatchRequestInfo<*>>,
        signRequest: Boolean = true
    ): Promise<List<BatchResponse<*>>, Exception> {
        return getResponseBody(request, signRequest = signRequest).map { batch ->
            val results = JsonUtil.fromJson(batch, List::class.java) ?: throw Error.ParsingFailed
            results.mapIndexed { idx, result ->
                val response = result as? Map<*, *> ?: throw Error.ParsingFailed
                val code = response["code"] as Int
                BatchResponse(
                    endpoint = requests[idx].endpoint,
                    code = code,
                    headers = response["headers"] as Map<String, String>,
                    body = if (code in 200..299) {
                        requests[idx].responseType?.let { respType ->
                            JsonUtil.toJson(response["body"]).takeIf { it != "[]" }?.let {
                                JsonUtil.fromJson(it, respType)
                            } ?: response["body"]
                        }

                    } else null
                )
            }
        }
    }

    fun getDefaultServerCapabilities(): Promise<List<String>, Exception> {
        return GlobalScope.asyncPromise { getOrFetchServerCapabilities(defaultServer) }
    }

    fun getDefaultRoomsIfNeeded(): Promise<List<DefaultGroup>, Exception> {
        return GlobalScope.asyncPromise {
            val groups = getAllRooms().await()

            val earlyGroups = groups.map { group ->
                DefaultGroup(group.token, group.name, null)
            }
            // See if we have any cached rooms, and if they already have images don't overwrite them with early non-image results
            defaultRooms.replayCache.firstOrNull()?.let { replayed ->
                if (replayed.none { it.image?.isNotEmpty() == true }) {
                    defaultRooms.tryEmit(earlyGroups)
                }
            }
            val images = groups.associate { group ->
                group.token to group.imageId?.let { downloadOpenGroupProfilePicture(defaultServer, group.token, it, signRequest = false) }
            }
            groups.map { group ->
                val image = try {
                    images[group.token]!!.await()
                } catch (e: Exception) {
                    // No image or image failed to download
                    null
                }
                DefaultGroup(group.token, group.name, image)
            }.also(defaultRooms::tryEmit)
        }
    }

    private fun getAllRooms(): Promise<List<RoomInfoDetails>, Exception> {
        val request = Request(
            verb = GET,
            room = null,
            server = defaultServer,
            endpoint = Endpoint.Rooms
        )
        return getResponseBody(request, signRequest = false).map { response ->
            MessagingModuleConfiguration.shared.json
                .decodeFromStream<Array<RoomInfoDetails>>(response.inputStream())
                .toList()
        }
    }

    fun getCapabilities(server: String, serverPubKeyHex: String? = null): Promise<Capabilities, Exception> {
        val request = Request(verb = GET, room = null, server = server, endpoint = Endpoint.Capabilities)
        return getResponseBody(request, signRequest = false, serverPubKeyHex).map { response ->
            JsonUtil.fromJson(response, Capabilities::class.java)
        }
    }

    fun sendDirectMessage(message: String, blindedAccountId: String, server: String): Promise<DirectMessage, Exception> {
        val request = Request(
            verb = POST,
            room = null,
            server = server,
            endpoint = Endpoint.InboxFor(blindedAccountId),
            parameters = mapOf("message" to message)
        )
        return getResponseBody(request).map { response ->
            JsonUtil.fromJson(response, DirectMessage::class.java)
        }
    }

    fun deleteAllInboxMessages(server: String): Promise<Map<*, *>, java.lang.Exception> {
        val request = Request(
            verb = DELETE,
            room = null,
            server = server,
            endpoint = Endpoint.Inbox
        )
        return getResponseBody(request).map { response ->
            JsonUtil.fromJson(response, Map::class.java)
        }
    }

    // endregion
}