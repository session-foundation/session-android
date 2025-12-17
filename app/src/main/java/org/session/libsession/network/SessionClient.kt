package org.session.libsession.network

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.Hash
import network.loki.messenger.libsession_util.SessionEncrypt
import org.session.libsession.network.model.ErrorStatus
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.OnionError
import org.session.libsession.network.onion.FailureDecision
import org.session.libsession.network.onion.OnionErrorManager
import org.session.libsession.network.onion.OnionFailureContext
import org.session.libsession.network.onion.Version
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.snode.model.BatchResponse
import org.session.libsession.snode.model.RetrieveMessageResponse
import org.session.libsession.snode.model.StoreMessageResponse
import org.session.libsession.utilities.mapValuesNotNull
import org.session.libsession.utilities.toByteArray
import org.session.libsignal.crypto.shuffledRandom
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.ByteArraySlice
import org.session.libsignal.utilities.ByteArraySlice.Companion.view
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.get

/**
 * High-level client for interacting with snodes.
 */
@Singleton
class SessionClient @Inject constructor(
    private val sessionNetwork: SessionNetwork,
    private val swarmDirectory: SwarmDirectory,
    private val snodeDirectory: SnodeDirectory,
    private val snodeClock: SnodeClock,
    private val json: Json,
    private val errorManager: OnionErrorManager
) {

    //todo ONION missing retry strategies

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val batchedRequestsSender: SendChannel<RequestInfo>

    init {
        val batchRequests = Channel<RequestInfo>(capacity = Channel.UNLIMITED)
        batchedRequestsSender = batchRequests

        val batchWindowMs = 100L

        data class BatchKey(val snodeAddress: String, val snodePort: Int, val publicKey: String, val sequence: Boolean, val version: Version)

        scope.launch {
            val batches = hashMapOf<BatchKey, MutableList<RequestInfo>>()

            while (true) {
                val batch: List<RequestInfo>? = select {
                    // If we receive a request, add it to the batch
                    batchRequests.onReceive { req ->
                        val key = BatchKey(req.snode.address, req.snode.port, req.publicKey, req.sequence, req.version)
                        batches.getOrPut(key) { mutableListOf() }.add(req)
                        null
                    }

                    // If we have anything in the batch, look for the one that is about to expire
                    // and wait for it to expire, remove it from the batches and send it for
                    // processing.
                    if (batches.isNotEmpty()) {
                        val earliestBatch = batches.minBy { it.value.first().requestTimeMs }
                        val deadline = earliestBatch.value.first().requestTimeMs + batchWindowMs
                        onTimeout((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)) {
                            batches.remove(earliestBatch.key)
                        }
                    }
                }

                if (batch == null) continue

                scope.launch {
                    val snode = batch.first().snode
                    val pubKey = batch.first().publicKey
                    val sequence = batch.first().sequence
                    val version = batch.first().version

                    val batchResponse: BatchResponse = try {
                        getBatchResponse(
                            snode = snode,
                            publicKey = pubKey,
                            requests = batch.map { it.request },
                            sequence = sequence,
                            version = version
                        )
                    } catch (e: Throwable) {
                        for (req in batch) runCatching { req.callback.send(Result.failure<Any>(e)) }
                        for (req in batch) req.callback.close()
                        return@launch
                    }

                    val items = batchResponse.results
                    val count = minOf(batch.size, items.size)

                    for (i in 0 until count) {
                        val req = batch[i]
                        val item = items[i]

                        val result: Result<Any> = runCatching {
                            if (!item.isSuccessful) throw BatchResponse.Error(item)

                            // Decode each sub-response body into expected type
                            @Suppress("UNCHECKED_CAST")
                            json.decodeFromJsonElement(
                                deserializer = req.responseType as DeserializationStrategy<Any>,
                                element = item.body
                            )
                        }

                        runCatching { req.callback.send(result) }
                    }

                    // If mismatch, fail remaining
                    if (items.size < batch.size) {
                        val err = Error.Generic("Batch response contained ${items.size} items for ${batch.size} requests")
                        for (i in items.size until batch.size) {
                            runCatching { batch[i].callback.send(Result.failure<Any>(err)) }
                        }
                    }

                    for (req in batch) req.callback.close()
                }
            }
        }
    }

    private suspend fun invokeRaw(
        method: Snode.Method,
        snode: Snode,
        parameters: Map<String, Any>,
        publicKey: String? = null,
        version: Version = Version.V4
    ): ByteArraySlice {
        val result = sessionNetwork.sendToSnode(
            method = method,
            parameters = parameters,
            snode = snode,
            publicKey = publicKey,
            version = version
        )

        if (result.isFailure) {
            throw result.exceptionOrNull()
                ?: Error.Generic("Unknown error invoking $method on $snode")
        }

        val onionResponse = result.getOrThrow()
        return onionResponse.body
            ?: throw Error.Generic("Empty body from snode for method $method")
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun <Res> invokeTyped(
        method: Snode.Method,
        snode: Snode,
        parameters: Map<String, Any>,
        responseDeserializationStrategy: DeserializationStrategy<Res>,
        publicKey: String? = null,
        version: Version = Version.V4
    ): Res {
        val body = invokeRaw(
            method = method,
            snode = snode,
            parameters = parameters,
            publicKey = publicKey,
            version = version
        )

        return body.inputStream().use { inputStream ->
            json.decodeFromStream(
                deserializer = responseDeserializationStrategy,
                stream = inputStream
            )
        }
    }

    suspend fun invoke(
        method: Snode.Method,
        snode: Snode,
        parameters: Map<String, Any>,
        publicKey: String? = null,
        version: Version = Version.V4
    ): Map<*, *> {
        val body = invokeRaw(
            method = method,
            snode = snode,
            parameters = parameters,
            publicKey = publicKey,
            version = version
        )

        @Suppress("UNCHECKED_CAST")
        return JsonUtil.fromJson(body, Map::class.java) as Map<*, *>
    }

    //todo ONION the methods below haven't been fully refactored - This is part of the next step of this refactor

    // Client methods

    /**
     * Note: After this method returns, [auth] will not be used by any of async calls and it's afe
     * for the caller to clean up the associated resources if needed.
     */
    suspend fun sendMessage(
        message: SnodeMessage,
        auth: SwarmAuth?,
        namespace: Int = 0,
        version: Version = Version.V4
    ): StoreMessageResponse {
        val params: Map<String, Any> = if (auth != null) {
            check(auth.accountId.hexString == message.recipient) {
                "Message sent to ${message.recipient} but authenticated with ${auth.accountId.hexString}"
            }

            val timestamp = snodeClock.currentTimeMills()

            buildAuthenticatedParameters(
                auth = auth,
                namespace = namespace,
                verificationData = { ns, t -> "${Snode.Method.SendMessage.rawValue}$ns$t" },
                timestamp = timestamp
            ) {
                put("sig_timestamp", timestamp)
                putAll(message.toJSON())
            }
        } else {
            buildMap {
                putAll(message.toJSON())
                if (namespace != 0) put("namespace", namespace)
            }
        }

        val target = swarmDirectory.getSingleTargetSnode(message.recipient)

        return sendBatchRequest(
            snode = target,
            publicKey = message.recipient,
            request = SnodeBatchRequestInfo(
                method = Snode.Method.SendMessage.rawValue,
                params = params,
                namespace = namespace
            ),
            responseType = StoreMessageResponse.serializer(),
            sequence = false,
            version = version
        )
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun deleteMessage(
        publicKey: String,
        auth: SwarmAuth,
        serverHashes: List<String>,
        version: Version = Version.V4
    ): Map<*, *> {
        val snode = swarmDirectory.getSingleTargetSnode(publicKey)

        val params = buildAuthenticatedParameters(
            auth = auth,
            namespace = null,
            verificationData = { _, _ ->
                buildString {
                    append(Snode.Method.DeleteMessage.rawValue)
                    serverHashes.forEach(this::append)
                }
            }
        ) {
            this["messages"] = serverHashes
        }

        val rawResponse = invoke(
            method = Snode.Method.DeleteMessage,
            snode = snode,
            parameters = params,
            publicKey = publicKey,
            version = version
        )

        val swarms = rawResponse["swarm"] as? Map<String, Any> ?: throw Error.Generic("Missing swarm in delete response")

        val deletedMessages: Map<String, Boolean> = swarms.mapValuesNotNull { (hexSnodePublicKey, rawJSON) ->
            val json = rawJSON as? Map<String, Any> ?: return@mapValuesNotNull null

            val isFailed = json["failed"] as? Boolean ?: false
            val statusCode = json["code"]?.toString()
            val reason = json["reason"] as? String

            if (isFailed) {
                Log.d("SessionClient", "DeleteMessage failed on $hexSnodePublicKey: $reason ($statusCode)")
                false
            } else {
                val hashes = (json["deleted"] as? List<*>)?.filterIsInstance<String>()
                    ?: return@mapValuesNotNull false

                val signature = json["signature"] as? String
                    ?: return@mapValuesNotNull false

                // Signature: ( PUBKEY_HEX || RMSG[0]..RMSG[N] || DMSG[0]..DMSG[M] )
                val message = sequenceOf(auth.accountId.hexString)
                    .plus(serverHashes)
                    .plus(hashes)
                    .toByteArray()

                ED25519.verify(
                    ed25519PublicKey = Hex.fromStringCondensed(hexSnodePublicKey),
                    signature = Base64.decode(signature),
                    message = message
                )
            }
        }

        if (deletedMessages.entries.all { !it.value }) {
            throw Error.Generic("DeleteMessage did not succeed on any swarm member")
        }

        return rawResponse
    }


    suspend fun deleteAllMessages(
        auth: SwarmAuth,
        version: Version = Version.V4
    ): Map<String, Boolean> {
        val publicKey = auth.accountId.hexString
        val snode = swarmDirectory.getSingleTargetSnode(publicKey)

        // Prefer network-adjusted time for signature compatibility
        val timestamp = snodeClock.waitForNetworkAdjustedTime()

        val params = buildAuthenticatedParameters(
            auth = auth,
            namespace = null,
            verificationData = { _, t -> "${Snode.Method.DeleteAll.rawValue}all$t" },
            timestamp = timestamp
        ) {
            put("namespace", "all")
        }

        val raw = invoke(
            method = Snode.Method.DeleteAll,
            snode = snode,
            parameters = params,
            publicKey = publicKey,
            version = version
        )

        return parseDeletions(
            userPublicKey = publicKey,
            timestamp = timestamp,
            rawResponse = raw
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseDeletions(userPublicKey: String, timestamp: Long, rawResponse: Map<*, *>): Map<String, Boolean> =
        (rawResponse["swarm"] as? Map<String, Any>)?.mapValuesNotNull { (hexSnodePublicKey, rawJSON) ->
            val json = rawJSON as? Map<String, Any> ?: return@mapValuesNotNull null
            if (json["failed"] as? Boolean == true) {
                val reason = json["reason"] as? String
                val statusCode = json["code"]?.toString()
                Log.e("Loki", "Failed to delete all messages from: $hexSnodePublicKey due to error: $reason ($statusCode).")
                false
            } else {
                val hashes = (json["deleted"] as Map<String,List<String>>).flatMap { (_, hashes) -> hashes }.sorted() // Hashes of deleted messages
                val signature = json["signature"] as String
                // The signature looks like ( PUBKEY_HEX || TIMESTAMP || DELETEDHASH[0] || ... || DELETEDHASH[N] )
                val message = sequenceOf(userPublicKey, "$timestamp").plus(hashes).toByteArray()
                ED25519.verify(
                    ed25519PublicKey = Hex.fromStringCondensed(hexSnodePublicKey),
                    signature = Base64.decode(signature),
                    message = message,
                )
            }
        } ?: mapOf()

    suspend fun getNetworkTime(
        snode: Snode,
        version: Version = Version.V4
    ): Pair<Snode, Long> {
        val json = invoke(
            method = Snode.Method.Info,
            snode = snode,
            parameters = emptyMap(),
            version = version
        )

        val timestamp = when (val t = json["timestamp"]) {
            is Long -> t
            is Int -> t.toLong()
            is Double -> t.toLong()
            else -> -1
        }

        return snode to timestamp
    }

    suspend fun getAccountID(onsName: String): String {
        val validationCount = 3
        val onsNameLower = onsName.lowercase(Locale.US)

        val params: Map<String, Any> = buildMap {
            this["method"] = "ons_resolve"
            this["params"] = buildMap<String, Any> {
                this["type"] = 0
                this["name_hash"] = Base64.encodeBytes(Hash.hash32(onsNameLower.toByteArray()))
            }
        }

        // Ask 3 different snodes
        val results = mutableListOf<String>()

        repeat(validationCount) {
            val snode = snodeDirectory.getRandomSnode()

            val json = invoke(
                method = Snode.Method.OxenDaemonRPCCall,
                snode = snode,
                parameters = params,
                version = Version.V4
            )

            @Suppress("UNCHECKED_CAST")
            val intermediate = json["result"] as? Map<*, *>
                ?: throw Error.Generic("Invalid ONS response: missing 'result'")

            val hexEncodedCiphertext = intermediate["encrypted_value"] as? String
                ?: throw Error.Generic("Invalid ONS response: missing 'encrypted_value'")

            val ciphertext = Hex.fromStringCondensed(hexEncodedCiphertext)
            val nonce = (intermediate["nonce"] as? String)?.let(Hex::fromStringCondensed)

            val accountId = SessionEncrypt.decryptOnsResponse(
                lowercaseName = onsNameLower,
                ciphertext = ciphertext,
                nonce = nonce
            )

            results += accountId
        }

        // All 3 must be equal for us to trust the result
        if (results.size == validationCount && results.toSet().size == 1) {
            return results.first()
        } else {
            throw Error.ValidationFailed
        }
    }

    suspend fun alterTtl(
        auth: SwarmAuth,
        messageHashes: List<String>,
        newExpiry: Long,
        shorten: Boolean = false,
        extend: Boolean = false,
        version: Version = Version.V4
    ): Map<*, *> {
        val snode = swarmDirectory.getSingleTargetSnode(auth.accountId.hexString)
        val params = buildAlterTtlParams(auth, messageHashes, newExpiry, shorten, extend)

        return invoke(
            method = Snode.Method.Expire,
            snode = snode,
            parameters = params,
            publicKey = auth.accountId.hexString,
            version = version
        )
    }


    // Batch logic

    private fun buildAuthenticatedParameters(
        auth: SwarmAuth,
        namespace: Int?,
        verificationData: ((namespaceText: String, timestamp: Long) -> Any)? = null,
        timestamp: Long = snodeClock.currentTimeMills(),
        builder: MutableMap<String, Any>.() -> Unit = {}
    ): Map<String, Any> {
        return buildMap {
            this.builder()

            if (verificationData != null) {
                val namespaceText = when (namespace) {
                    null, 0 -> ""
                    else -> namespace.toString()
                }

                val verifyData = when (val v = verificationData(namespaceText, timestamp)) {
                    is String -> v.toByteArray()
                    is ByteArray -> v
                    else -> throw IllegalArgumentException("verificationData must return String or ByteArray")
                }

                putAll(auth.sign(verifyData))
                put("timestamp", timestamp)
            }

            put("pubkey", auth.accountId.hexString)
            if (namespace != null && namespace != 0) put("namespace", namespace)
            auth.ed25519PublicKeyHex?.let { put("pubkey_ed25519", it) }
        }
    }

    /**
     * Typed batch/sequence response envelope.
     */
    suspend fun getBatchResponse(
        snode: Snode,
        publicKey: String,
        requests: List<SnodeBatchRequestInfo>,
        sequence: Boolean = false,
        version: Version = Version.V4
    ): BatchResponse {
        val method = if (sequence) Snode.Method.Sequence else Snode.Method.Batch
        val response = invokeTyped(
            method = method,
            snode = snode,
            parameters = mapOf("requests" to requests),
            responseDeserializationStrategy = BatchResponse.serializer(),
            publicKey = publicKey,
            version = version
        )

        // IMPORTANT: batch subresponse failures do not go through OnionErrorManager
        // because the outer response is usually 200.
        val firstFailed = response.results.firstOrNull { !it.isSuccessful }
        if (firstFailed != null) {
            handleBatchItemFailure(
                targetSnode = snode,
                publicKey = publicKey,
                item = firstFailed
            )
        }

        return response
    }

    private suspend fun handleBatchItemFailure(
        item: BatchResponse.Item,
        targetSnode: Snode,
        publicKey: String?,
    ) : FailureDecision {
        //todo ONION can we think of a better way to integrate batching with error handling? Right now this is a temporary way to fit it into our system
        // we might be missing things like the path or the message

        val bodySlice = item.body.toString().toByteArray(Charsets.UTF_8).view()

        // we synthesise a DestinationError since what we get at this point is from the destination's response
        val err = OnionError.DestinationError(
            ErrorStatus(code = item.code, message = null, body = bodySlice)
        )

        return errorManager.onFailure(
            error = err,
            ctx = OnionFailureContext(
                path = listOf(targetSnode),
                destination = OnionDestination.SnodeDestination(targetSnode),
                targetSnode = targetSnode,
                publicKey = publicKey
            )
        )
    }

    /**
     * Convenience: single-request batching (coalesced for ~100ms).
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> sendBatchRequest(
        snode: Snode,
        publicKey: String,
        request: SnodeBatchRequestInfo,
        responseType: DeserializationStrategy<T>,
        sequence: Boolean = false,
        version: Version = Version.V4
    ): T {
        val callback = Channel<Result<Any>>(capacity = 1)

        batchedRequestsSender.send(
            RequestInfo(
                snode = snode,
                publicKey = publicKey,
                request = request,
                responseType = responseType,
                callback = callback,
                sequence = sequence,
                version = version
            )
        )

        try {
            return callback.receive().getOrThrow() as T
        } catch (e: CancellationException) {
            // Close the channel if the coroutine is cancelled, so the batch processing won't
            // handle this one (best effort only)
            callback.close()
            throw e
        }
    }

    suspend fun sendBatchRequest(
        snode: Snode,
        publicKey: String,
        request: SnodeBatchRequestInfo,
        sequence: Boolean = false,
        version: Version = Version.V4
    ): JsonElement {
        return sendBatchRequest(
            snode = snode,
            publicKey = publicKey,
            request = request,
            responseType = JsonElement.serializer(),
            sequence = sequence,
            version = version
        )
    }

    fun buildAuthenticatedAlterTtlBatchRequest(
        auth: SwarmAuth,
        messageHashes: List<String>,
        newExpiry: Long,
        shorten: Boolean = false,
        extend: Boolean = false
    ): SnodeBatchRequestInfo {
        val params = buildAlterTtlParams(auth, messageHashes, newExpiry, shorten, extend)
        return SnodeBatchRequestInfo(
            method = Snode.Method.Expire.rawValue,
            params = params,
            namespace = null
        )
    }

    private fun buildAlterTtlParams(
        auth: SwarmAuth,
        messageHashes: List<String>,
        newExpiry: Long,
        shorten: Boolean,
        extend: Boolean
    ): Map<String, Any> {
        val modifier = when {
            extend -> "extend"
            shorten -> "shorten"
            else -> ""
        }

        return buildAuthenticatedParameters(
            auth = auth,
            namespace = null,
            verificationData = { _, _ ->
                buildString {
                    append(Snode.Method.Expire.rawValue)
                    append(modifier)
                    append(newExpiry.toString())
                    messageHashes.forEach(this::append)
                }
            }
        ) {
            put("expiry", newExpiry)
            put("messages", messageHashes)
            when {
                extend -> put("extend", true)
                shorten -> put("shorten", true)
            }
        }
    }

    fun buildAuthenticatedStoreBatchInfo(
        namespace: Int,
        message: SnodeMessage,
        auth: SwarmAuth,
    ): SnodeBatchRequestInfo {
        check(message.recipient == auth.accountId.hexString) {
            "Message sent to ${message.recipient} but authenticated with ${auth.accountId.hexString}"
        }

        val params = buildAuthenticatedParameters(
            namespace = namespace,
            auth = auth,
            verificationData = { ns, t -> "${Snode.Method.SendMessage.rawValue}$ns$t" },
        ) {
            putAll(message.toJSON())
        }

        return SnodeBatchRequestInfo(
            method = Snode.Method.SendMessage.rawValue,
            params = params,
            namespace = namespace
        )
    }

    fun buildAuthenticatedRetrieveBatchRequest(
        auth: SwarmAuth,
        lastHash: String?,
        namespace: Int = 0,
        maxSize: Int? = null
    ): SnodeBatchRequestInfo {
        val params = buildAuthenticatedParameters(
            namespace = namespace,
            auth = auth,
            verificationData = { ns, t -> "${Snode.Method.Retrieve.rawValue}$ns$t" },
        ) {
            put("last_hash", lastHash.orEmpty())
            if (maxSize != null) put("max_size", maxSize)
        }

        return SnodeBatchRequestInfo(
            method = Snode.Method.Retrieve.rawValue,
            params = params,
            namespace = namespace
        )
    }

    fun buildAuthenticatedDeleteBatchInfo(
        auth: SwarmAuth,
        messageHashes: List<String>,
        required: Boolean = false
    ): SnodeBatchRequestInfo {
        val params = buildAuthenticatedParameters(
            namespace = null,
            auth = auth,
            verificationData = { _, _ ->
                buildString {
                    append(Snode.Method.DeleteMessage.rawValue)
                    messageHashes.forEach(this::append)
                }
            }
        ) {
            put("messages", messageHashes)
            put("required", required)
        }

        return SnodeBatchRequestInfo(
            method = Snode.Method.DeleteMessage.rawValue,
            params = params,
            namespace = null
        )
    }

    fun buildAuthenticatedUnrevokeSubKeyBatchRequest(
        groupAdminAuth: SwarmAuth,
        subAccountTokens: List<ByteArray>,
    ): SnodeBatchRequestInfo {
        val params = buildAuthenticatedParameters(
            namespace = null,
            auth = groupAdminAuth,
            verificationData = { _, t ->
                subAccountTokens.fold(
                    "${Snode.Method.UnrevokeSubAccount.rawValue}$t".toByteArray()
                ) { acc, subAccount -> acc + subAccount }
            }
        ) {
            put("unrevoke", subAccountTokens.map(Base64::encodeBytes))
        }

        return SnodeBatchRequestInfo(
            method = Snode.Method.UnrevokeSubAccount.rawValue,
            params = params,
            namespace = null
        )
    }

    fun buildAuthenticatedRevokeSubKeyBatchRequest(
        groupAdminAuth: SwarmAuth,
        subAccountTokens: List<ByteArray>,
    ): SnodeBatchRequestInfo {
        val params = buildAuthenticatedParameters(
            namespace = null,
            auth = groupAdminAuth,
            verificationData = { _, t ->
                subAccountTokens.fold(
                    "${Snode.Method.RevokeSubAccount.rawValue}$t".toByteArray()
                ) { acc, subAccount -> acc + subAccount }
            }
        ) {
            put("revoke", subAccountTokens.map(Base64::encodeBytes))
        }

        return SnodeBatchRequestInfo(
            method = Snode.Method.RevokeSubAccount.rawValue,
            params = params,
            namespace = null
        )
    }


    data class SnodeBatchRequestInfo(
        val method: String,
        val params: Map<String, Any>,
        @Transient val namespace: Int?,
    )

    private data class RequestInfo(
        val snode: Snode,
        val publicKey: String,
        val request: SnodeBatchRequestInfo,
        val responseType: DeserializationStrategy<*>,
        val callback: SendChannel<Result<Any>>,
        val requestTimeMs: Long = SystemClock.elapsedRealtime(),
        val sequence: Boolean = false,
        val version: Version = Version.V4,
    )

    // Error
    sealed class Error(val description: String) : Exception(description) {
        data class Generic(val info: String = "An error occurred.") : Error(info)
        object ValidationFailed : Error("ONS name validation failed.")
    }
}
