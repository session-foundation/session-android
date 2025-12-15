package org.session.libsession.network

import network.loki.messenger.libsession_util.Hash
import network.loki.messenger.libsession_util.SessionEncrypt
import org.session.libsession.network.onion.Version
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.network.snode.SwarmDirectory
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.SwarmAuth
import org.session.libsignal.crypto.shuffledRandom
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level client for interacting with snodes
 */
@Singleton
class SessionClient @Inject constructor(
    private val sessionNetwork: SessionNetwork,
    private val swarmDirectory: SwarmDirectory,
    private val snodeDirectory: SnodeDirectory,
    private val snodeClock: SnodeClock,
) {

    //todo ONION no retry logic atm
    //todo ONION missing alterTTL
    //todo ONION missing batch logic
    //todo ONION missing snode error handling

    /**
     * - Uses onion routing via SessionNetwork.
     * - Expects the snode to return a JSON body (storage_rpc style).
     * - Returns that JSON as a Map for now.
     *
     * NOTE: This does *not* do any snode-failure accounting yet; that will be layered
     *       on later (e.g. path/snode penalisation based on error codes).
     */
    suspend fun invoke(
        method: Snode.Method,
        snode: Snode,
        parameters: Map<String, Any>,
        version: Version = Version.V4
    ): Map<*, *> {
        val result = sessionNetwork.sendToSnode(
            method = method,
            parameters = parameters,
            snode = snode,
            version = version
        )

        if (result.isFailure) {
            throw result.exceptionOrNull()
                ?: Error.Generic("Unknown error invoking $method on $snode")
        }

        val onionResponse = result.getOrThrow()
        val body = onionResponse.body
            ?: throw Error.Generic("Empty body from snode for method $method")

        @Suppress("UNCHECKED_CAST")
        return JsonUtil.fromJson(body, Map::class.java) as Map<*, *>
    }

    /**
     * Rough equivalent of old getSingleTargetSnode(publicKey).
     *
     * Picks one snode from the user's swarm for a given account.
     * We deliberately randomise to avoid hammering a single node.
     */
    private suspend fun getSingleTargetSnode(publicKey: String): Snode {
        val swarm = swarmDirectory.getSwarm(publicKey)
        require(swarm.isNotEmpty()) {
            "Swarm is empty for pubkey=$publicKey"
        }
        // Old code used shuffledRandom(); we can approximate with shuffled() then random()
        return swarm.shuffledRandom().random()
    }

    /**
     * Build parameters required to call authenticated storage API.
     *
     * @param auth The authentication data required to sign the request
     * @param namespace The namespace of the messages. Null if not relevant.
     * @param verificationData A function that returns the data to be signed.
     *                         It gets the namespace text and timestamp.
     * @param timestamp The timestamp to be used in the request. Default is network-adjusted time.
     * @param builder Lambda for additional custom parameters.
     */
    private fun buildAuthenticatedParameters(
        auth: SwarmAuth,
        namespace: Int?,
        verificationData: ((namespaceText: String, timestamp: Long) -> Any)? = null,
        timestamp: Long = snodeClock.currentTimeMills(),
        builder: MutableMap<String, Any>.() -> Unit = {}
    ): Map<String, Any> {
        return buildMap {
            // Callers can add their own params first
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
            if (namespace != null && namespace != 0) {
                put("namespace", namespace)
            }

            auth.ed25519PublicKeyHex?.let { put("pubkey_ed25519", it) }
        }
    }

    /**
     * Rough port of old SnodeAPI.sendMessage, but:
     * - No additional "outer" retry layer yet (we rely on SessionNetwork's onion retry).
     * - No batching; we send a single SendMessage RPC.
     *
     * TODO:
     *  - Wire in higher-level retryWithUniformInterval-style behaviour if needed.
     *  - Return a strongly typed StoreMessageResponse once the model & serialization are wired.
     */
    suspend fun sendMessage(
        message: SnodeMessage,
        auth: SwarmAuth?,
        namespace: Int = 0,
        version: Version = Version.V4
    ): Map<*, *> {
        val params: Map<String, Any> = if (auth != null) {
            check(auth.accountId.hexString == message.recipient) {
                "Message sent to ${message.recipient} but authenticated with ${auth.accountId.hexString}"
            }

            val timestamp = snodeClock.currentTimeMills()

            buildAuthenticatedParameters(
                auth = auth,
                namespace = namespace,
                verificationData = { ns, t ->
                    "${Snode.Method.SendMessage.rawValue}$ns$t"
                },
                timestamp = timestamp
            ) {
                put("sig_timestamp", timestamp)
                putAll(message.toJSON())
            }
        } else {
            buildMap {
                putAll(message.toJSON())
                if (namespace != 0) {
                    put("namespace", namespace)
                }
            }
        }

        val target = getSingleTargetSnode(message.recipient)

        Log.d("SessionClient", "Sending message to ${target.address}:${target.port} for ${message.recipient}")

        // In old code this went through batch API; here we do a simple single-RPC SendMessage.
        val json = invoke(
            method = Snode.Method.SendMessage,
            snode = target,
            parameters = params,
            version = version
        )

        // Later you can map this Map<*, *> into StoreMessageResponse via kotlinx.serialization.
        return json
    }

    /**
     * Simplified version of old SnodeAPI.deleteMessage.
     *
     * Differences vs old code:
     *  - We do NOT (yet) verify the per-snode signatures of deletions.
     *  - We do a single DeleteMessage RPC; no extra retryWithUniformInterval wrapper for now.
     *  - We return the raw JSON map so you can layer richer logic on top later.
     */
    suspend fun deleteMessage(
        publicKey: String,
        auth: SwarmAuth,
        serverHashes: List<String>,
        version: Version = Version.V4
    ): Map<*, *> {
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

        val snode = getSingleTargetSnode(publicKey)

        Log.d("SessionClient", "Deleting messages on ${snode.address}:${snode.port} for $publicKey")

        val json = invoke(
            method = Snode.Method.DeleteMessage,
            snode = snode,
            parameters = params,
            version = version
        )

        // Old code walked json["swarm"] and verified ED25519 signatures.
        // You can port that verification logic into a separate helper later if you want parity.
        return json
    }

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

        val timestamp = json["timestamp"] as? Long
            ?: throw Error.Generic("Missing 'timestamp' in Info response")

        return snode to timestamp
    }

    /**
     * Resolve an ONS name into an account ID (33-byte value as hex string).
     *
     * Rough port of old SnodeAPI.getAccountID:
     *  - Lowercases the name.
     *  - Asks 3 different snodes for the ONS resolution.
     *  - Decrypts the result and requires all 3 to match.
     *
     * Throws if validation fails.
     */
    suspend fun getAccountID(onsName: String): String {
        val validationCount = 3
        val onsNameLower = onsName.lowercase(Locale.US)

        // Build request params for ons_resolve via OxenDaemonRPCCall
        val params: Map<String, Any> = buildMap {
            this["method"] = "ons_resolve"
            this["params"] = buildMap<String, Any> {
                this["type"] = 0 // session account type
                this["name_hash"] = Base64.encodeBytes(
                    Hash.hash32(onsNameLower.toByteArray())
                )
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

    // Error
    sealed class Error(val description: String) : Exception(description) {
        data class Generic(val info: String = "An error occurred.") : Error(info)

        // ONS
        object ValidationFailed : Error("ONS name validation failed.")
    }
}
