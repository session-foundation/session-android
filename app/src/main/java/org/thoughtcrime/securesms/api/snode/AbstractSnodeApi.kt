package org.thoughtcrime.securesms.api.snode

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.session.libsession.snode.SwarmAuth
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.error.ErrorWithFailureDecision

abstract class AbstractSnodeApi<RespType : SnodeApiResponse>(
    private val snodeApiErrorManager: SnodeApiErrorManager,
) : SnodeApi<RespType> {
    final override fun buildRequest(ctx: ApiExecutorContext): SnodeJsonRequest {
        return SnodeJsonRequest(
            method = methodName,
            params = buildParams(ctx)
        )
    }

    abstract val methodName: String
    abstract fun buildParams(ctx: ApiExecutorContext): JsonElement

    final override suspend fun handleResponse(
        ctx: ApiExecutorContext,
        snode: Snode,
        code: Int,
        body: JsonElement?
    ): RespType {
        if (code in 200..299) {
            return deserializeSuccessResponse(ctx, checkNotNull(body) {
                "Expected non-null body for successful response"
            })
        } else {
            val failureContext = ctx.getOrPut(SnodeClientFailureKey) {
                SnodeClientFailureContext(
                    previousErrorCode = null
                )
            }

            val (error, decision) = snodeApiErrorManager.onFailure(
                errorCode = code,
                bodyText = (body as? JsonPrimitive)?.let { p ->
                    p.content.takeIf { p.isString }
                },
                snode = snode,
                ctx = failureContext
            )

            Log.d("SnodeApi", "Network error for a Snode endpoint (${snode.ed25519Key}), with status:${code} - error: $error")

            ctx.set(SnodeClientFailureKey, failureContext.copy(previousErrorCode = code))

            if (decision != null) {
                throw ErrorWithFailureDecision(
                    cause = error,
                    failureDecision = decision,
                )
            } else {
                throw error
            }
        }
    }

    protected abstract fun deserializeSuccessResponse(ctx: ApiExecutorContext, body: JsonElement): RespType
}

fun buildAuthenticatedParameters(
    auth: SwarmAuth,
    namespace: Int?,
    timestamp: Long,
    verificationData: ((namespaceText: String, timestamp: Long) -> Any)? = null,
    builder: MutableMap<String, JsonElement>.() -> Unit = {}
): JsonObject {
    return JsonObject(buildMap {
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