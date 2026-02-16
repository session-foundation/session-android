package org.session.libsession.network.onion

import androidx.collection.arrayMapOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToStream
import org.session.libsession.network.model.OnionDestination
import org.session.libsession.utilities.AESGCM
import org.session.libsession.utilities.AESGCM.EncryptionResult
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.toHexString
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

class OnionRequestEncryption @Inject constructor(
    private val json: Json,
) {

    @OptIn(ExperimentalSerializationApi::class)
    fun encode(ciphertext: ByteArray, payload: JsonElement): ByteArray {
        // The encoding of V2 onion requests looks like: | 4 bytes: size N of ciphertext | N bytes: ciphertext | json as utf8 |
        val jsonAsData = ByteArrayOutputStream().use { os ->
            json.encodeToStream(payload, os)
            os.toByteArray()
        }
        val output = ByteArray(4 + ciphertext.size + jsonAsData.size)

        ByteBuffer.wrap(output).apply {
            order(ByteOrder.LITTLE_ENDIAN).putInt(ciphertext.size)
            put(ciphertext)
            put(jsonAsData)
        }

        return output
    }

    /**
     * Encrypts `payload` for `destination` and returns the result. Use this to build the core of an onion request.
     */
    fun encryptPayloadForDestination(
        payload: ByteArray,
        destination: OnionDestination,
        onionRequestVersion: OnionRequestVersion
    ): EncryptionResult {
        val plaintext = if (onionRequestVersion == OnionRequestVersion.V4) {
            payload
        } else {
            // Wrapping isn't needed for file server or open group onion requests
            when (destination) {
                is OnionDestination.SnodeDestination -> encode(payload,
                    JsonObject(mapOf("headers" to JsonPrimitive(""))))
                is OnionDestination.ServerDestination -> payload
            }
        }
        val x25519PublicKey = when (destination) {
            is OnionDestination.SnodeDestination -> destination.snode.publicKeySet!!.x25519Key
            is OnionDestination.ServerDestination -> destination.x25519PublicKey
        }
        return AESGCM.encrypt(plaintext, x25519PublicKey)
    }

    /**
     * Encrypts the previous encryption result (i.e. that of the hop after this one) for this hop. Use this to build the layers of an onion request.
     */
    fun encryptHop(lhs: OnionDestination, rhs: OnionDestination, previousEncryptionResult: EncryptionResult): EncryptionResult {
        val payload: MutableMap<String, JsonElement> = when (rhs) {
            is OnionDestination.SnodeDestination -> {
                arrayMapOf("destination" to JsonPrimitive(rhs.snode.publicKeySet!!.ed25519Key))
            }

            is OnionDestination.ServerDestination -> {
                arrayMapOf(
                    "host" to JsonPrimitive(rhs.host),
                    "target" to JsonPrimitive(rhs.target),
                    "method" to JsonPrimitive("POST"),
                    "protocol" to JsonPrimitive(rhs.scheme),
                    "port" to JsonPrimitive(rhs.port)
                )
            }
        }
        payload["ephemeral_key"] = JsonPrimitive(previousEncryptionResult.ephemeralPublicKey.toHexString())
        val x25519PublicKey = when (lhs) {
            is OnionDestination.SnodeDestination -> {
                lhs.snode.publicKeySet!!.x25519Key
            }

            is OnionDestination.ServerDestination -> {
                lhs.x25519PublicKey
            }
        }
        val plaintext = encode(previousEncryptionResult.ciphertext, JsonObject(payload))
        return AESGCM.encrypt(plaintext, x25519PublicKey)
    }
}
