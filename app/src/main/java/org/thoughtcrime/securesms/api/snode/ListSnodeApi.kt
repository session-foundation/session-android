package org.thoughtcrime.securesms.api.snode

import android.util.Base64
import android.util.Base64InputStream
import androidx.compose.ui.platform.LocalGraphicsContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import network.loki.messenger.libsession_util.Curve25519
import okio.EOFException
import okio.buffer
import okio.source
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.api.ApiExecutorContext
import java.net.Inet4Address
import javax.inject.Inject

class ListSnodeApi @Inject constructor(
    errorManager: SnodeApiErrorManager,
) : AbstractSnodeApi<ListSnodeApi.Response>(errorManager) {
    override fun deserializeSuccessResponse(
        ctx: ApiExecutorContext,
        body: JsonElement
    ): Response {
        val b64 = (body as JsonPrimitive).content

        val ed25519PubKeyBuffer = ByteArray(32)
        val zeroEd25519PubKey = ByteArray(ed25519PubKeyBuffer.size)
        val ipBytes = ByteArray(4)
        val versionBytes = ByteArray(3)

        val snodes = mutableListOf<SnodeInfo>()

        // Decode the base64 string and read the snode info out from the binary as:
        // - 32 byte Ed25519 pubkey
        // - 8 byte u64 swarm ID, in network order.
        // - 4 bytes public ip, in network (big-endian) order (i.e. 1.2.3.4 is "\x01\x02\x03\x04")
        // - 2 byte https port, in network order (i.e. port 259 is "\x01\x03")
        // - 2 byte OMQ (TCP)/QUIC (UDP) port, in network order
        // - 3 byte storage server version, e.g. 1.2.3 is "\x01\x02\x03"
        b64.byteInputStream().use { rawStream ->
            Base64InputStream(rawStream, Base64.DEFAULT)
                .source()
                .buffer()
                .use { bufferedSource ->
                    while (true) {
                        try {
                            bufferedSource.readFully(ed25519PubKeyBuffer)
                            val swarmId = bufferedSource.readLong()
                            bufferedSource.readFully(ipBytes)
                            val httpsPort = bufferedSource.readShort().toUShort().toInt()
                            val omqPort = bufferedSource.readShort().toUShort().toInt()
                            bufferedSource.readFully(versionBytes)

                            val x25519PubKey = runCatching {
                                if (!ed25519PubKeyBuffer.contentEquals(zeroEd25519PubKey)) {
                                    Curve25519.pubKeyFromED25519(ed25519PubKeyBuffer)
                                } else {
                                    error("Invalid ed25519 pubkey")
                                }
                            }.onFailure {
                                Log.w("ListSnodeApi", "Invalid ed25519 pub key", it)
                            }.getOrNull() ?: continue

                            snodes += SnodeInfo(
                                ip = Inet4Address.getByAddress(ipBytes).hostAddress!!,
                                port = httpsPort,
                                ed25519PubKey = Hex.toStringCondensed(ed25519PubKeyBuffer),
                                x25519PubKey = Hex.toStringCondensed(x25519PubKey)
                            )
                        } catch (_: EOFException) {
                            break
                        }
                    }
                }
        }


        return Response(snodes)
    }

    override val methodName: String get() = "active_nodes_bin"
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
        val ip: String? = null,
        @SerialName(KEY_PORT)
        val port: Int? = null,
        @SerialName(KEY_ED25519)
        val ed25519PubKey: String,
        @SerialName(KEY_X25519)
        val x25519PubKey: String,
    ) {
        fun toSnode(): Snode? {
            return Snode(
                address = ip.takeUnless { it == "0.0.0.0" || it == "255.255.255.255" }?.let { "https://$it" } ?: return null,
                port = port ?: return null,
                publicKeySet = Snode.KeySet(ed25519PubKey, x25519PubKey),
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