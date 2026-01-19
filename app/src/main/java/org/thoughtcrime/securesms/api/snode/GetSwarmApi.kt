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
import org.session.libsignal.utilities.Snode

class GetSwarmApi @AssistedInject constructor(
    @Assisted private val pubKey: String,
    private val json: Json,
) : AbstractSnodeApi<GetSwarmApi.Response>() {
    override val methodName: String
        get() = "get_snodes_for_pubkey"

    override fun buildParams(): JsonObject {
        return JsonObject(
            mapOf("pubkey" to JsonPrimitive(pubKey))
        )
    }

    override fun deserializeSuccessResponse(body: JsonElement): Response {
        return json.decodeFromJsonElement(Response.serializer(), body)
    }

    @Serializable
    class Response(val snodes: List<SnodeInfo>): SnodeApiResponse

    @Serializable
    class SnodeInfo(
        val ip: String,
        val port: Int,
        @SerialName("pubkey_ed25519")
        val ed25519PubKey: String,
        @SerialName("pubkey_x25519")
        val x25519PubKey: String,
    ) {
        fun toSnode(): Snode? {
            return Snode(
                ip.takeUnless { it == "0.0.0.0" }?.let { "https://$it" } ?: return null,
                port,
                Snode.KeySet(ed25519PubKey, x25519PubKey),
                Snode.Version.ZERO,
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(pubKey: String): GetSwarmApi
    }
}