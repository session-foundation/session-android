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
        val useOnionRouting: Boolean = true,
        val dynamicHeaders: (suspend () -> Map<String, String>)? = null
    )

    private fun createBody(body: ByteArray?, parameters: Any?): RequestBody? {
        if (body != null) return RequestBody.create("application/octet-stream".toMediaType(), body)
        if (parameters == null) return null
        val parametersAsJSON = JsonUtil.toJson(parameters)
        return RequestBody.create("application/json".toMediaType(), parametersAsJSON)
    }

    private suspend fun getResponseBody(
        request: Request,
        signRequest: Boolean = true,
        serverPubKeyHex: String? = null
    ): ByteArraySlice {
        val response =  send(request, signRequest = signRequest, serverPubKeyHex = serverPubKeyHex, operationName = "OpenGroupAPI.getResponseBody")

        return response.body ?: throw Error.ParsingFailed
    }

    private suspend fun getResponseBodyJson(
        request: Request,
        signRequest: Boolean = true,
        serverPubKeyHex: String? = null
    ): Map<*, *> {
        val response =  send(request, signRequest = signRequest, serverPubKeyHex = serverPubKeyHex, operationName = "OpenGroupAPI.getResponseBodyJson")
        return JsonUtil.fromJson(response.body, Map::class.java)
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

    private suspend fun send(request: Request, signRequest: Boolean, serverPubKeyHex: String? = null, operationName: String): OnionResponse {
        request.server.toHttpUrlOrNull() ?: throw(Error.InvalidURL)

        val urlBuilder = StringBuilder("${request.server}/${request.endpoint.value}")
        if (request.verb == GET && request.queryParameters.isNotEmpty()) {
            urlBuilder.append("?")
            for ((key, value) in request.queryParameters) {
                urlBuilder.append("$key=$value")
            }
        }
        val urlRequest = urlBuilder.toString()

        val serverPublicKey = serverPubKeyHex
            ?: MessagingModuleConfiguration.shared.storage.getOpenGroupPublicKey(request.server)
            ?: throw Error.NoPublicKey

        if (!request.useOnionRouting) {
            throw IllegalStateException("It's currently not allowed to send non onion routed requests.")
        }

        try {
            return MessagingModuleConfiguration.shared.serverClient.send(
                operationName = operationName,
                requestFactory = {
                    val base = request.headers.toMutableMap()

                    val computed = request.dynamicHeaders?.invoke().orEmpty()
                    base.putAll(computed)

                    if (signRequest) {
                        val signingHeaders = buildSogsSigningHeaders(
                            request = request,
                            serverPublicKey = serverPublicKey
                        )
                        base.putAll(signingHeaders)
                    }

                    val builder = okhttp3.Request.Builder()
                        .url(urlRequest)
                        .headers(base.toHeaders())

                    when (request.verb) {
                        GET -> builder.get()
                        PUT -> builder.put(createBody(request.body, request.parameters)!!)
                        POST -> builder.post(createBody(request.body, request.parameters)!!)
                        DELETE -> builder.delete(createBody(request.body, request.parameters))
                    }

                    if (!request.room.isNullOrEmpty()) {
                        builder.header("Room", request.room)
                    }

                    builder.build()
                },
                serverBaseUrl = request.server,
                x25519PublicKey = serverPublicKey
            )
        } catch (e: Exception) {
            //todo ONION handle the case where we get a 400 with "Invalid authentication: this server requires the use of blinded ids" - call capabilities once and retry
            when (e) {
                is OnionError -> Log.e("SOGS", "Failed onion request: ${e.message}", e)
                else -> Log.e("SOGS", "Failed onion request", e)
            }
            throw e
        }
    }

    private suspend fun buildSogsSigningHeaders(
        request: Request,
        serverPublicKey: String
    ): Map<String, String> {
        val serverCapabilities = getOrFetchServerCapabilities(request.server)

        val ed25519KeyPair = MessagingModuleConfiguration.shared.storage.getUserED25519KeyPair()
            ?: throw Error.NoEd25519KeyPair

        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }

        // If you want “strict after COS”: use waitForNetworkAdjustedTime()/1000
        val timestamp = TimeUnit.MILLISECONDS.toSeconds(
            MessagingModuleConfiguration.shared.snodeClock.currentTimeMills()
        )

        val bodyHash = when {
            request.parameters != null -> Hash.hash64(JsonUtil.toJson(request.parameters).toByteArray())
            request.body != null -> Hash.hash64(request.body)
            else -> byteArrayOf()
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

            signature = try {
                BlindKeyAPI.blind15Sign(
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

        return mapOf(
            "X-SOGS-Nonce" to encodeBytes(nonce),
            "X-SOGS-Timestamp" to "$timestamp",
            "X-SOGS-Pubkey" to pubKey,
            "X-SOGS-Signature" to encodeBytes(signature)
        )
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
        val json =  getResponseBodyJson(request, signRequest = true)
        return json["id"]?.toString() ?: throw Error.ParsingFailed
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
    ): OpenGroupMessage {
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
        val json =  getResponseBodyJson(request, signRequest = true)
        @Suppress("UNCHECKED_CAST") val rawMessage = json as? Map<String, Any>
            ?: throw Error.ParsingFailed
        val result = OpenGroupMessage.fromJSON(rawMessage) ?: throw Error.ParsingFailed
        val storage = MessagingModuleConfiguration.shared.storage
        storage.addReceivedMessageTimestamp(result.sentTimestamp)
        return result
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
        send(request, signRequest = true, operationName = "OpenGroupAPI.deleteMessage")
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

        send(request, signRequest = true, operationName = "OpenGroupAPI.ban")
        Log.d("Loki", "Banned user: $publicKey from: $server.$room.")
    }

    suspend fun banAndDeleteAll(publicKey: String, room: String, server: String) {

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
        sequentialBatch(server, requests)
        Log.d("Loki", "Banned user: $publicKey from: $server.$room.")
    }

    suspend fun unban(publicKey: String, room: String, server: String) {
        val request = Request(verb = DELETE, room = room, server = server, endpoint = Endpoint.UserUnban(publicKey))
        send(request, signRequest = true, operationName = "OpenGroupAPI.unban")
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
        requests: MutableList<BatchRequestInfo<*>>,
        signRequest: Boolean = true
    ): List<BatchResponse<*>> {
        val batch = getResponseBody(request, signRequest = signRequest)
        val results = JsonUtil.fromJson(batch, List::class.java) ?: throw Error.ParsingFailed
        return results.mapIndexed { idx, result ->
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
        val response = getResponseBody(
            request = request,
            signRequest = false,
            serverPubKeyHex = defaultServerPublicKey
        )

        return MessagingModuleConfiguration.shared.json
            .decodeFromStream<Array<RoomInfoDetails>>(response.inputStream()).toList()
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