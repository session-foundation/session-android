package org.thoughtcrime.securesms.api.snode

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import network.loki.messenger.libsession_util.Hash
import network.loki.messenger.libsession_util.SessionEncrypt
import org.session.libsession.network.SnodeClientErrorManager
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Hex

class OnsResolveApi @AssistedInject constructor(
    @Assisted private val name: String,
    errorManager: SnodeClientErrorManager,
    private val json: Json,
) : AbstractSnodeApi<String>(errorManager) {
    override val methodName: String get() = "oxend_request"

    override fun buildParams(): JsonElement {
        val normalizedName = name.lowercase()

        return json.encodeToJsonElement(OxendRequest(
            endpoint = "ons_resolve",
            params = JsonObject(mapOf(
                "type" to JsonPrimitive(0),
                "name_hash" to JsonPrimitive(Base64.encodeBytes(Hash.hash32(normalizedName.toByteArray())))
            ))
        ))
    }

    override fun deserializeSuccessResponse(requestParams: JsonElement, body: JsonElement): String {
        val response = json.decodeFromJsonElement<OxendResponse>(body)

        val ciphertext = Hex.fromStringCondensed(response.result.encryptedValue)
        val nonce = Hex.fromStringCondensed(response.result.nonce)

        return SessionEncrypt.decryptOnsResponse(
            lowercaseName = name.lowercase(),
            ciphertext = ciphertext,
            nonce = nonce
        )
    }


    @Serializable
    private class OxendRequest(
        val endpoint: String,
        val params: JsonObject,
    )

    @Serializable
    private class OxendResponse(
        val result: OnsRecord
    )

    @Serializable
    private class OnsRecord(
        @SerialName("encrypted_value")
        val encryptedValue: String,
        val nonce: String,
    )

    @AssistedFactory
    interface Factory {
        fun create(name: String): OnsResolveApi
    }
}