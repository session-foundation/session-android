package org.thoughtcrime.securesms.api.snode

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.long
import network.loki.messenger.libsession_util.ED25519
import org.session.libsession.network.SnodeClientErrorManager
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.SwarmAuth
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Hex

class DeleteAllMessageApi @AssistedInject constructor(
    @Assisted private val auth: SwarmAuth,
    errorManager: SnodeClientErrorManager,
    private val snodeClock: SnodeClock,
    private val json: Json,
) : AbstractSnodeApi<Map<String, Boolean>>(errorManager) {
    override val methodName: String get() = "delete_all"

    override fun deserializeSuccessResponse(requestParams: JsonElement, body: JsonElement): Map<String, Boolean> {
        requestParams as JsonObject

        val params = requestParams["params"] as JsonObject
        val timestamp = (params["timestamp"] as JsonPrimitive).long

        return json.decodeFromJsonElement<Response>(body)
            .swarm
            .mapValues { (snodePubKeyHex, state) ->
                !state.failed && state.verify(
                    snodePubKeyHex = snodePubKeyHex,
                    timestamp = timestamp,
                    userPublicKey = auth.accountId.hexString
                )
            }
    }

    override fun buildParams(): JsonElement {
        return buildAuthenticatedParameters(
            auth = auth,
            namespace = null,
            verificationData = { _, t -> "${methodName}all$t" },
            timestamp = snodeClock.currentTimeMillis()
        ) {
            put("namespace", JsonPrimitive("all"))
        }
    }

    @Serializable
    private class Response(
        val swarm: Map<String, SnodeDeleteState>
    )

    @Serializable
    private class SnodeDeleteState(
        val failed: Boolean = false,
        val code: Int? = null,
        val reason: String? = null,
        val deleted: Map<String, List<String>> = emptyMap(),
        val signature: String? = null
    ) {
        fun verify(snodePubKeyHex: String,
                   userPublicKey: String,
                   timestamp: Long): Boolean {
            val hashes = deleted.flatMap { it.value }
            val message = buildString {
                append(userPublicKey)
                append("$timestamp")
                hashes.forEach(this::append)
            }.toByteArray()

            return ED25519.verify(
                ed25519PublicKey = Hex.fromStringCondensed(snodePubKeyHex),
                signature = Base64.decode(signature),
                message = message
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(auth: SwarmAuth): DeleteAllMessageApi
    }
}