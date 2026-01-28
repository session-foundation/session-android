package org.thoughtcrime.securesms.api.snode

import androidx.collection.arraySetOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext
import javax.inject.Inject

class ListSnodeApi @Inject constructor(
    private val json: Json,
    errorManager: SnodeApiErrorManager,
) : AbstractSnodeApi<ListSnodeApi.Response>(errorManager) {
    override fun deserializeSuccessResponse(
        ctx: ApiExecutorContext,
        body: JsonElement
    ): Response {
        return json.decodeFromJsonElement(body)
    }

    override val methodName: String get() = "get_n_service_nodes"
    override fun buildParams(ctx: ApiExecutorContext): JsonElement = buildRequestJson()

    @Serializable
    class Response(
        @SerialName("service_node_states")
        val nodes: List<SnodeInfo>
    ) {
        fun toSnodeList(): List<Snode> {
            return nodes.mapNotNull { it.toSnode() }
        }
    }

    @Serializable
    class SnodeInfo(
        @SerialName(KEY_IP)
        val ip: String,
        @SerialName(KEY_PORT)
        val port: Int,
        @SerialName(KEY_ED25519)
        val ed25519PubKey: String,
        @SerialName(KEY_X25519)
        val x25519PubKey: String,
    ) {
        fun toSnode(): Snode? {
            return Snode(
                ip.takeUnless { it == "0.0.0.0" }?.let { "https://$it" } ?: return null,
                port,
                Snode.KeySet(ed25519PubKey, x25519PubKey),
            )
        }
    }

    companion object {
        private const val KEY_IP = "public_ip"
        private const val KEY_PORT = "storage_port"
        private const val KEY_X25519 = "pubkey_x25519"
        private const val KEY_ED25519 = "pubkey_ed25519"

        fun buildRequestJson(): JsonElement {
            return JsonObject(mapOf(
                "active_only" to JsonPrimitive(true),
                "fields" to JsonArray(
                    listOf(
                        JsonPrimitive(KEY_IP),
                        JsonPrimitive(KEY_PORT),
                        JsonPrimitive(KEY_X25519),
                        JsonPrimitive(KEY_ED25519),
                    )
                )
            ))
        }
    }
}