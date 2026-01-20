package org.thoughtcrime.securesms.api.snode

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.session.libsession.network.SnodeClientErrorManager
import org.session.libsession.network.SnodeClientFailureContext
import org.session.libsession.network.SnodeClientFailureKey
import org.session.libsession.snode.SwarmAuth
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision
import kotlin.collections.component1
import kotlin.collections.component2

abstract class AbstractSnodeApi<RespType : SnodeApiResponse>(
    private val snodeClientErrorManager: SnodeClientErrorManager,
) : SnodeApi<RespType> {
    final override suspend fun handleResponse(
        ctx: ApiExecutorContext,
        snode: Snode,
        code: Int,
        body: JsonElement?
    ): RespType {
        if (code in 200..299) {
            return deserializeSuccessResponse(checkNotNull(body) {
                "Expected non-null body for successful response"
            })
        } else {
            val failureContext = ctx.getOrPut(SnodeClientFailureKey) {
                SnodeClientFailureContext(
                    targetSnode = snode,
                    swarmPublicKey = snode.publicKeySet?.x25519Key,
                    previousErrorCode = null
                )
            }

            val decision = snodeClientErrorManager.onFailure(
                code = code,
                bodyText = (body as? JsonPrimitive)?.let { p ->
                    p.content.takeIf { p.isString }
                },
                ctx = failureContext
            )

            ctx.set(SnodeClientFailureKey, failureContext.copy(previousErrorCode = code))

            if (decision != null) {
                throw ErrorWithFailureDecision(
                    cause = RuntimeException("Snode API error: code=$code, body=$body"),
                    failureDecision = decision,
                )
            } else {
                throw SnodeApiError.UnknownStatusCode(
                    code = code,
                    bodyText = (body as? JsonPrimitive)?.content
                )
            }
        }
    }

    abstract fun deserializeSuccessResponse(body: JsonElement): RespType
}

fun buildAuthenticatedParameters(
    auth: SwarmAuth,
    namespace: Int?,
    timestamp: Long,
    verificationData: ((namespaceText: String, timestamp: Long) -> Any)? = null,
    builder: MutableMap<String, JsonPrimitive>.() -> Unit = {}
): JsonObject {
    return JsonObject(buildMap<String, JsonPrimitive> {
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

            auth.sign(verifyData)
                .forEach { (key, value) ->
                    this[key] = JsonPrimitive(value)
                }

            put("timestamp", JsonPrimitive(timestamp))
        }

        put("pubkey", JsonPrimitive(auth.accountId.hexString))
        if (namespace != null && namespace != 0) put("namespace", JsonPrimitive(namespace))
        auth.ed25519PublicKeyHex?.let { put("pubkey_ed25519", JsonPrimitive(it)) }
    })
}