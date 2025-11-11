package org.session.libsession.messaging.open_groups

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.Hash
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.snode.OnionResponse
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.serializable.InstantAsSecondsDoubleSerializer
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
import java.time.Instant
import java.util.concurrent.TimeUnit

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
        val responseSerializer: DeserializationStrategy<T>,
    )

    @Serializable
    data class BatchRequest(
        val method: HTTP.Verb,
        val path: String,
        val headers: Map<String, String> = emptyMap(),
        val json: JsonElement? = null,
        val b64: String? = null,
    ) {
        init {
            check(json == null || b64 == null) { "BatchRequest can have either 'json' or 'b64' body, not both." }
        }
    }

    @Serializable
    private data class APIBatchResponse(
        val code: Int,
        val headers: Map<String, String>,
        val body: JsonElement,
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

    @Serializable
    data class Message(
        val id : Long = 0,
        val sessionId: String = "",
        @Serializable(InstantAsSecondsDoubleSerializer::class)
        val posted: Instant? = null,
        @Serializable(InstantAsSecondsDoubleSerializer::class)
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

    data class Request(
        val verb: HTTP.Verb,
        val room: String?,
        val server: String,
        val endpoint: Endpoint,
        val queryParameters: Map<String, String> = mapOf(),
        val parameters: JsonElement? = null,
        val headers: Map<String, String> = mapOf(),
        val body: ByteArray? = null,
        /**
         * Always `true` under normal circumstances. You might want to disable
         * this when running over Lokinet.
         */
        val useOnionRouting: Boolean = true
    )

    private fun createBody(body: ByteArray?, parameters: JsonElement?): RequestBody? {
        if (body != null) return RequestBody.create("application/octet-stream".toMediaType(), body)
        if (parameters == null) return null
        val parametersAsJSON = MessagingModuleConfiguration.shared.json.encodeToString(parameters)
        return RequestBody.create("application/json".toMediaType(), parametersAsJSON)
    }

    private suspend fun getResponseBody(
        request: Request,
        signRequest: Boolean = true,
        serverPubKeyHex: String? = null
    ): ByteArraySlice {
        val response =  send(request, signRequest = signRequest, serverPubKeyHex = serverPubKeyHex)

        return response.body ?: throw Error.ParsingFailed
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend inline fun <reified T> getResponseBodyJson(
        request: Request,
        signRequest: Boolean = true,
        serverPubKeyHex: String? = null
    ): T {
        return send(request, signRequest = signRequest, serverPubKeyHex = serverPubKeyHex)
            .body!!
            .inputStream()
            .use {
                MessagingModuleConfiguration.shared
                    .json
                    .decodeFromStream<T>(it)
            }
    }

    suspend fun getOrFetchServerCapabilities(server: String): List<String> {
        val storage = MessagingModuleConfiguration.shared.storage
        val caps = storage.getServerCapabilities(server)

        if (caps != null) {
            return caps
        }

        val fetched = getCapabilities(server,
            serverPubKeyHex = defaultServerPublicKey.takeIf { server == defaultServer }
        )

        storage.setServerCapabilities(server, fetched.capabilities)
        return fetched.capabilities
    }

    private suspend fun send(request: Request, signRequest: Boolean, serverPubKeyHex: String? = null): OnionResponse {
        request.server.toHttpUrlOrNull() ?: throw(Error.InvalidURL)

        val urlBuilder = StringBuilder("${request.server}/${request.endpoint.value}")
        if (request.verb == GET && request.queryParameters.isNotEmpty()) {
            urlBuilder.append("?")
            for ((key, value) in request.queryParameters) {
                urlBuilder.append("$key=$value")
            }
        }

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

    suspend fun downloadOpenGroupProfilePicture(
        server: String,
        roomID: String,
        imageId: String,
        signRequest: Boolean = true,
        serverPubKeyHex: String? = null,
    ): ByteArraySlice {
        val request = Request(
            verb = GET,
            room = roomID,
            server = server,
            endpoint = Endpoint.RoomFileIndividual(roomID, imageId)
        )
        return getResponseBody(request, signRequest = signRequest, serverPubKeyHex = serverPubKeyHex)
    }

    // region Upload/Download
    @Serializable
    private data class UploadResult(val id: String)

    suspend fun upload(file: ByteArray, room: String, server: String): String {
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

        return getResponseBodyJson<UploadResult>(request, signRequest = true).id
    }

    suspend fun download(fileId: String, room: String, server: String): ByteArraySlice {
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
    suspend fun sendMessage(
        message: OpenGroupMessage,
        room: String,
        server: String,
        whisperTo: List<String>? = null,
        whisperMods: Boolean? = null,
        fileIds: List<String>? = null
    ): Message {
        val signedMessage = message.sign(server) ?:throw Error.SigningFailed
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
        val msg = getResponseBodyJson<Message>(request, signRequest = true)
        msg.posted?.let {
            MessagingModuleConfiguration.shared.storage.addReceivedMessageTimestamp(it.toEpochMilli())
        }
        return msg
    }
    // endregion

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun addReaction(room: String, server: String, messageId: Long, emoji: String): AddReactionResponse {
        val request = Request(
            verb = PUT,
            room = room,
            server = server,
            endpoint = Endpoint.Reaction(room, messageId, emoji),
            parameters = emptyMap<String, String>()
        )

        val response = getResponseBody(request, signRequest = true)
        val reaction: AddReactionResponse =  response.inputStream().use( MessagingModuleConfiguration.shared.json::decodeFromStream)

        return reaction
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun deleteReaction(room: String, server: String, messageId: Long, emoji: String): DeleteReactionResponse {
        val request = Request(
            verb = DELETE,
            room = room,
            server = server,
            endpoint = Endpoint.Reaction(room, messageId, emoji)
        )

        val response = getResponseBody(request, signRequest = true)
        val reaction: DeleteReactionResponse = MessagingModuleConfiguration.shared.json.decodeFromStream(response.inputStream())

        return reaction
    }

    suspend fun deleteAllReactions(room: String, server: String, messageId: Long, emoji: String): DeleteAllReactionsResponse {
        val request = Request(
            verb = DELETE,
            room = room,
            server = server,
            endpoint = Endpoint.ReactionDelete(room, messageId, emoji)
        )
        val response = getResponseBody(request, signRequest = true)
        return JsonUtil.fromJson(response, DeleteAllReactionsResponse::class.java)
    }
    // endregion

    // region Message Deletion
    suspend fun deleteMessage(serverID: Long, room: String, server: String) {
        val request = Request(verb = DELETE, room = room, server = server, endpoint = Endpoint.RoomMessageIndividual(room, serverID))
        send(request, signRequest = true)
        Log.d("Loki", "Message deletion successful.")
    }

    // endregion

    // region Moderation
    suspend fun ban(publicKey: String, room: String, server: String) {
        val parameters =  mapOf("rooms" to listOf(room))
        val request = Request(
            verb = POST,
            room = room,
            server = server,
            endpoint = Endpoint.UserBan(publicKey),
            parameters = parameters
        )

        send(request, signRequest = true)
        Log.d("Loki", "Banned user: $publicKey from: $server.$room.")
    }

    suspend fun banAndDeleteAll(publicKey: String, room: String, server: String) {

        val requests = mutableListOf<BatchRequestInfo<*>>(
            // Ban request
            BatchRequestInfo(
                request = BatchRequest(
                    method = POST,
                    path = "/user/$publicKey/ban",
                    json = JsonObject(mapOf("rooms" to JsonArray(listOf(JsonPrimitive(room))))),
                ),
                endpoint = Endpoint.UserBan(publicKey),
                responseSerializer = Unit.serializer(),
            ),
            // Delete request
            BatchRequestInfo(
                request = BatchRequest(DELETE, "/room/$room/all/$publicKey"),
                endpoint = Endpoint.RoomDeleteMessages(room, publicKey),
                responseSerializer = Unit.serializer()
            )
        )
        sequentialBatch(server, requests)
        Log.d("Loki", "Banned user: $publicKey from: $server.$room.")
    }

    suspend fun unban(publicKey: String, room: String, server: String) {
        val request = Request(verb = DELETE, room = room, server = server, endpoint = Endpoint.UserUnban(publicKey))
        send(request, signRequest = true)
        Log.d("Loki", "Unbanned user: $publicKey from: $server.$room")
    }
    // endregion

    // region General

    suspend fun parallelBatch(
        server: String,
        requests: MutableList<BatchRequestInfo<*>>
    ): List<BatchResponse<*>> {
        val request = Request(
            verb = POST,
            room = null,
            server = server,
            endpoint = Endpoint.Batch,
            parameters = requests.map { it.request }
        )
        return getBatchResponseJson(request, requests)
    }

    private suspend fun sequentialBatch(
        server: String,
        requests: MutableList<BatchRequestInfo<*>>
    ): List<BatchResponse<*>> {
        val request = Request(
            verb = POST,
            room = null,
            server = server,
            endpoint = Endpoint.Sequence,
            parameters = requests.map { it.request }
        )
        return getBatchResponseJson(request, requests)
    }

    private suspend fun getBatchResponseJson(
        request: Request,
        requests: List<BatchRequestInfo<*>>,
        signRequest: Boolean = true
    ): List<BatchResponse<*>> {
        val batch = getResponseBodyJson<List<APIBatchResponse>>(request = request, signRequest = signRequest)
        check(batch.size <= requests.size) {
            "Batch response size (${batch.size}) is larger than request size (${requests.size})."
        }

        return batch.mapIndexed { idx, result ->
            val requestInfo = requests[idx]
            BatchResponse(
                endpoint = requestInfo.endpoint,
                code = result.code,
                headers = result.headers,
                body = if (result.code in 200..299) {
                    MessagingModuleConfiguration.shared.json.decodeFromJsonElement(
                        requestInfo.responseSerializer,
                        result.body
                    )
                } else null
            )
        }
    }

    suspend fun getDefaultServerCapabilities(): List<String> {
        return getOrFetchServerCapabilities(defaultServer)
    }

    suspend fun getDefaultRoomsIfNeeded(): List<DefaultGroup> {
        val groups = getAllRooms()

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
            group.token to group.imageId?.let { downloadOpenGroupProfilePicture(
                server = defaultServer,
                roomID = group.token,
                imageId = it,
                signRequest = false,
                serverPubKeyHex = defaultServerPublicKey,
            ) }
        }

        return groups.map { group ->
            val image = try {
                images[group.token]!!
            } catch (e: Exception) {
                // No image or image failed to download
                null
            }
            DefaultGroup(group.token, group.name, image)
        }.also(defaultRooms::tryEmit)
    }

    private suspend fun getAllRooms(): List<RoomInfoDetails> {
        val request = Request(
            verb = GET,
            room = null,
            server = defaultServer,
            endpoint = Endpoint.Rooms
        )
        return getResponseBodyJson(
            request = request,
            signRequest = false,
            serverPubKeyHex = defaultServerPublicKey
        )
    }

    suspend fun getCapabilities(server: String, serverPubKeyHex: String? = null): Capabilities {
        val request = Request(verb = GET, room = null, server = server, endpoint = Endpoint.Capabilities)
        val response = getResponseBody(request, signRequest = false, serverPubKeyHex)
        return JsonUtil.fromJson(response, Capabilities::class.java)
    }

    suspend fun sendDirectMessage(message: String, blindedAccountId: String, server: String): DirectMessage {
        val request = Request(
            verb = POST,
            room = null,
            server = server,
            endpoint = Endpoint.InboxFor(blindedAccountId),
            parameters = mapOf("message" to message)
        )
        val response = getResponseBody(request)
        return JsonUtil.fromJson(response, DirectMessage::class.java)
    }

    suspend fun deleteAllInboxMessages(server: String): Map<*, *> {
        val request = Request(
            verb = DELETE,
            room = null,
            server = server,
            endpoint = Endpoint.Inbox
        )
        val response = getResponseBody(request)
        return JsonUtil.fromJson(response, Map::class.java)
    }

    // endregion
}