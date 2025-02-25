package org.session.libsession.utilities

import androidx.annotation.WorkerThread
import org.session.libsignal.crypto.CipherUtil.CIPHER_LOCK
import org.session.libsignal.utilities.ByteUtil
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.Util
import org.whispersystems.curve25519.Curve25519
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@WorkerThread
internal object AESGCM {

    /**
     * Tamaño de tag para AES/GCM en bits.
     */
    internal val gcmTagSize = 128

    /**
     * Tamaño de IV (nonce) para AES/GCM en bytes.
     */
    internal val ivSize = 12

    /**
     * Resultado de la operación de cifrado.
     *
     * - [ciphertext]: contenedor del IV (al inicio) + datos cifrados + tag GCM (al final).
     * - [symmetricKey]: la clave AES de 32 bytes derivada de ECDH + HMAC (opcional si el receptor la requiere).
     * - [ephemeralPublicKey]: la pública efímera (32 bytes) usada para derivar la clave.
     */
    internal data class EncryptionResult(
        internal val ciphertext: ByteArray,
        internal val symmetricKey: ByteArray,
        internal val ephemeralPublicKey: ByteArray
    )

    /**
     * Descifra un mensaje AES-GCM “IV + ciphertext + tag” usando la clave simétrica [symmetricKey].
     */
    @WorkerThread
    internal fun decrypt(ivAndCiphertext: ByteArray, symmetricKey: ByteArray): ByteArray {
        val iv = ivAndCiphertext.sliceArray(0 until ivSize)
        val ciphertext = ivAndCiphertext.sliceArray(ivSize until ivAndCiphertext.size)
        synchronized(CIPHER_LOCK) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(symmetricKey, "AES"),
                GCMParameterSpec(gcmTagSize, iv)
            )
            return cipher.doFinal(ciphertext)
        }
    }

    /**
     * Genera la clave simétrica de 32 bytes (AES-256) a partir de:
     *
     * 1) ECDH: Secreto compartido = (x25519PublicKey * x25519PrivateKey).
     * 2) HMAC-SHA256 (opcionalmente con un salt o info).
     *
     * Si se quiere más entropía, puede agregarse un salt aleatorio o “info” en la HMAC.
     */
    @WorkerThread
    internal fun generateSymmetricKey(
        x25519PublicKey: ByteArray,
        x25519PrivateKey: ByteArray
    ): ByteArray {
        // ECDH para obtener 32 bytes de secreto compartido
        val ephemeralSharedSecret = Curve25519.getInstance(Curve25519.BEST)
            .calculateAgreement(x25519PublicKey, x25519PrivateKey)

        // HMAC-SHA256 sobre ese secreto. Se puede usar un salt adicional si se desea.
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("LOKI".toByteArray(), "HmacSHA256"))
        return mac.doFinal(ephemeralSharedSecret)
    }

    /**
     * Cifra con AES-GCM dado un `symmetricKey`.
     *
     * Devuelve un array con IV (12 bytes) + ciphertext + tag (16 bytes).
     */
    @WorkerThread
    internal fun encrypt(plaintext: ByteArray, symmetricKey: ByteArray): ByteArray {
        val iv = Util.getSecretBytes(ivSize)  // IV aleatorio de 12 bytes
        synchronized(CIPHER_LOCK) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(symmetricKey, "AES"),
                GCMParameterSpec(gcmTagSize, iv)
            )
            // Combinamos IV + (datos cifrados + tag)
            return ByteUtil.combine(iv, cipher.doFinal(plaintext))
        }
    }

    /**
     * Encripta [plaintext] usando una clave derivada de (miPrivadaEfímera × la pública X25519 dada).
     * Retorna un [EncryptionResult] con:
     *   - ciphertext (IV+datos+tag),
     *   - la clave simétrica usada,
     *   - y la pública efímera (32 bytes).
     *
     * El receptor descifrará usando:
     *   - su clave privada,
     *   - ephemeralPublicKey (recibida en el mensaje),
     *   - y la misma lógica AES-GCM.
     */
    @WorkerThread
    internal fun encrypt(plaintext: ByteArray, hexEncodedX25519PublicKey: String): EncryptionResult {

        // 1) Obtenemos la pública X25519 de destino
        val x25519PublicKey = Hex.fromStringCondensed(hexEncodedX25519PublicKey)

        // 2) Generamos un par efímero local
        val ephemeralKeyPair = Curve25519.getInstance(Curve25519.BEST).generateKeyPair()
        val ephemeralPriv = ephemeralKeyPair.privateKey
        val ephemeralPub  = ephemeralKeyPair.publicKey

        // 3) Derivamos la clave AES con ECDH + HMAC-SHA256
        val symmetricKey = generateSymmetricKey(x25519PublicKey, ephemeralPriv)

        // 4) Ciframos el plaintext con AES-GCM
        val ciphertext = encrypt(plaintext, symmetricKey)

        // 5) Retornamos el ciphertext y la efímera (que el receptor necesitará)
        return EncryptionResult(ciphertext, symmetricKey, ephemeralPub)
    }

}
