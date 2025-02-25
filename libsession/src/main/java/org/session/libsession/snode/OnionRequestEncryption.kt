package org.session.libsession.snode

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import org.session.libsession.snode.OnionRequestAPI.Destination
import org.session.libsession.utilities.AESGCM
import org.session.libsession.utilities.AESGCM.EncryptionResult
import org.session.libsignal.utilities.toHexString
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.ThreadUtils
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

object OnionRequestEncryption {

    /**
     * Codifica (V2) la estructura onion: | 4 bytes (Little-Endian) = tamaño del ciphertext | ciphertext | json en UTF-8 |
     */
    internal fun encode(ciphertext: ByteArray, json: Map<*, *>): ByteArray {
        val jsonAsData = JsonUtil.toJson(json).toByteArray()
        val ciphertextSize = ciphertext.size
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(ciphertextSize)
        val ciphertextSizeAsData = ByteArray(buffer.capacity())
        (buffer as Buffer).position(0)
        buffer.get(ciphertextSizeAsData)
        return ciphertextSizeAsData + ciphertext + jsonAsData
    }

    /**
     * Cifra `payload` para [destination] usando `AESGCM.encrypt()` (que internamente hace crypto_box_seal).
     * Se retorna un [EncryptionResult] con [ciphertext] y [ephemeralPublicKey] (placeholder).
     */
    internal fun encryptPayloadForDestination(
        payload: ByteArray,
        destination: Destination,
        version: Version
    ): Promise<EncryptionResult, Exception> {
        val deferred = deferred<EncryptionResult, Exception>()
        ThreadUtils.queue {
            try {
                // Nota: en la versión v4 se pasa el payload tal cual;
                // en v2/v3 se hace un "encode" intermedio, etc.
                val plaintext = if (version == Version.V4) {
                    payload
                } else {
                    // Wrapping no siempre es necesario, depende del tipo de destino
                    when (destination) {
                        is Destination.Snode -> encode(payload, mapOf("headers" to ""))
                        is Destination.Server -> payload
                    }
                }

                val x25519PublicKey = when (destination) {
                    is Destination.Snode -> destination.snode.publicKeySet!!.x25519Key
                    is Destination.Server -> destination.x25519PublicKey
                }

                // Llamamos al nuevo AESGCM.encrypt() => sealed box
                val result = AESGCM.encrypt(plaintext, x25519PublicKey)
                // result = EncryptionResult(ciphertext, ephemeralPublicKey)

                deferred.resolve(result)
            } catch (exception: Exception) {
                deferred.reject(exception)
            }
        }
        return deferred.promise
    }

    /**
     * Cifra la capa actual (anterior [previousEncryptionResult]) para el hop [lhs].
     * Devuelve otro [EncryptionResult] con la capa onion actualizada.
     */
    internal fun encryptHop(
        lhs: Destination,
        rhs: Destination,
        previousEncryptionResult: EncryptionResult
    ): Promise<EncryptionResult, Exception> {
        val deferred = deferred<EncryptionResult, Exception>()
        ThreadUtils.queue {
            try {
                // Armamos un pequeño JSON con datos del hop siguiente
                val payload: MutableMap<String, Any> = when (rhs) {
                    is Destination.Snode -> {
                        mutableMapOf("destination" to rhs.snode.publicKeySet!!.ed25519Key)
                    }
                    is Destination.Server -> {
                        mutableMapOf(
                            "host" to rhs.host,
                            "target" to rhs.target,
                            "method" to "POST",
                            "protocol" to rhs.scheme,
                            "port" to rhs.port
                        )
                    }
                }
                // Se sigue pasando ephemeral_key como string hex, aunque sea un placeholder
                payload["ephemeral_key"] = previousEncryptionResult.ephemeralPublicKey.toHexString()

                val x25519PublicKey = when (lhs) {
                    is Destination.Snode -> lhs.snode.publicKeySet!!.x25519Key
                    is Destination.Server -> lhs.x25519PublicKey
                }

                // El plaintext de esta capa es "encode(ciphertextAnterior, JSONConDestino)"
                val plaintext = encode(previousEncryptionResult.ciphertext, payload)

                // Se vuelve a cifrar (sealed box)
                val result = AESGCM.encrypt(plaintext, x25519PublicKey)
                deferred.resolve(result)
            } catch (exception: Exception) {
                deferred.reject(exception)
            }
        }
        return deferred.promise
    }
}
