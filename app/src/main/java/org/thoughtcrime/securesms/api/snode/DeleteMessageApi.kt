package org.thoughtcrime.securesms.api.snode

import androidx.annotation.VisibleForTesting
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
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.SwarmAuth
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Hex
import org.thoughtcrime.securesms.api.ApiExecutorContext

class DeleteMessageApi @AssistedInject constructor(
    @Assisted private val swarmAuth: SwarmAuth,
    @Assisted private val messageHashes: Collection<String>,
    private val json: Json,
    private val snodeClock: SnodeClock,
    errorManager: SnodeApiErrorManager
) : AbstractSnodeApi<DeleteMessageApi.SuccessResponse>(errorManager) {

    override fun deserializeSuccessResponse(ctx: ApiExecutorContext, body: JsonElement): SuccessResponse {
        return parseSuccessResponse(
            json = json,
            body = body,
            requestedMessageHashes = messageHashes,
            swarmPubKeyHex = swarmAuth.accountId.hexString,
            verifySignature = { publicKey, signature, message ->
                ED25519.verify(ed25519PublicKey = publicKey, signature = signature, message = message)
            }
        )
    }

    override val methodName: String
        get() = "delete"

    override fun buildParams(ctx: ApiExecutorContext): JsonElement {
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
        // An empty `deleted` is still a success: the node signs it like any other result, and it
        // is what a hash that has already expired or been deleted returns. 1:1 message hashes
        // expire after 14 days, so rejecting it makes every older message fail to delete.
        fun verifyDeletedMessages(
            requestedMessageHashes: Collection<String>,
            snodePubKey: String,
            swarmPubKey: String,
            verifySignature: (ed25519PublicKey: ByteArray, signature: ByteArray, message: ByteArray) -> Boolean,
        ): Boolean {
            if (signature.isNullOrBlank()) return false

            val message = buildString {
                append(swarmPubKey)
                requestedMessageHashes.forEach(this::append)
                deleted.forEach(this::append)
            }.toByteArray()

            return verifySignature(
                Hex.fromStringCondensed(snodePubKey),
                Base64.decode(signature),
                message
            )
        }
    }

    companion object {
        @VisibleForTesting
        internal fun parseSuccessResponse(
            json: Json,
            body: JsonElement,
            requestedMessageHashes: Collection<String>,
            swarmPubKeyHex: String,
            verifySignature: (ed25519PublicKey: ByteArray, signature: ByteArray, message: ByteArray) -> Boolean,
        ): SuccessResponse {
            val response: Response = json.decodeFromJsonElement(body)

            val totalSuccessSnodes = response.swarm
                .asSequence()
                .count { (snodePubKeyHex, deleteState) ->
                    !deleteState.failed && deleteState.verifyDeletedMessages(
                        requestedMessageHashes = requestedMessageHashes,
                        snodePubKey = snodePubKeyHex,
                        swarmPubKey = swarmPubKeyHex,
                        verifySignature = verifySignature,
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
    }

    @AssistedFactory
    interface Factory {
        fun create(
            swarmAuth: SwarmAuth,
            messageHashes: Collection<String>
        ): DeleteMessageApi
    }
}