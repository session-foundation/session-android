package org.thoughtcrime.securesms.api.snode

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import network.loki.messenger.libsession_util.ED25519
import org.session.libsession.network.SnodeClientErrorManager
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.SwarmAuth
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Hex

class DeleteMessageApi @AssistedInject constructor(
    @Assisted private val swarmAuth: SwarmAuth,
    @Assisted private val messageHashes: Collection<String>,
    private val json: Json,
    private val snodeClock: SnodeClock,
    errorManager: SnodeClientErrorManager
) : AbstractSnodeApi<DeleteMessageApi.SuccessResponse>(errorManager) {

    override fun deserializeSuccessResponse(requestParams: JsonElement, body: JsonElement): SuccessResponse {
        val response: Response = json.decodeFromJsonElement(body)

        val totalSuccessSnodes = response.swarm
            .asSequence()
            .count { (snodePubKeyHex, deleteState) ->
                !deleteState.failed && deleteState.verifyDeletedMessages(
                    requestedMessageHashes = messageHashes,
                    snodePubKey = snodePubKeyHex,
                    swarmPubKey = swarmAuth.accountId.hexString
                )
            }

        check(totalSuccessSnodes > 0) {
            "No snodes reported successful deletion of messages"
        }

        return SuccessResponse(
            numSnodeSuccessDeleted = totalSuccessSnodes,
            numSnodeTotal = response.swarm.size
        )
    }

    override val methodName: String
        get() = "delete"

    override fun buildParams(): JsonElement {
        return buildAuthenticatedParameters(
            auth = swarmAuth,
            namespace = null,
            timestamp = snodeClock.currentTimeMillis(),
            verificationData = { _, _ ->
                buildString {
                    append(methodName)
                    messageHashes.forEach(this::append)
                }
            }
        ) {
            this["messages"] = JsonArray(messageHashes.map(::JsonPrimitive))
        }
    }

    data class SuccessResponse(
        val numSnodeSuccessDeleted: Int,
        val numSnodeTotal: Int
    ) {
        init {
            check(numSnodeSuccessDeleted in 0..numSnodeTotal) {
                "numSnodeSuccessDeleted must be between 0 and numSnodeTotal"
            }
        }
    }

    @Serializable
    private class Response(
        val swarm: Map<String, SnodeDeletionState> = emptyMap()
    )

    @Serializable
    private class SnodeDeletionState(
        val failed: Boolean = false,
        val code: Int? = null,
        val reason: String? = null,
        val deleted: List<String> = emptyList(),
        val signature: String? = null
    ) {
        fun verifyDeletedMessages(
            requestedMessageHashes: Collection<String>,
            snodePubKey: String,
            swarmPubKey: String,
        ): Boolean {
            if (deleted.isEmpty() || signature.isNullOrBlank()) return false

            val message = buildString {
                append(swarmPubKey)
                requestedMessageHashes.forEach(this::append)
                deleted.forEach(this::append)
            }.toByteArray()

            return ED25519.verify(
                ed25519PublicKey = Hex.fromStringCondensed(snodePubKey),
                signature = Base64.decode(signature),
                message = message
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            swarmAuth: SwarmAuth,
            messageHashes: Collection<String>
        ): DeleteMessageApi
    }
}