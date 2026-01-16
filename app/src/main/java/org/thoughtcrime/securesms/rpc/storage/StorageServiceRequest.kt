package org.thoughtcrime.securesms.rpc.storage

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.session.libsession.snode.SwarmAuth
import org.session.libsignal.utilities.Snode

interface StorageServiceResponse

interface StorageServiceRequest<RespType : StorageServiceResponse> {
    val methodName: String
    fun buildParams(): JsonObject
    fun deserializeResponse(snode: Snode, code: Int, body: JsonElement?): RespType
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