package org.session.libsession.utilities

import androidx.annotation.WorkerThread
import network.loki.messenger.libsession_util.Curve25519
import network.loki.messenger.libsession_util.SessionEncrypt
import org.session.libsignal.crypto.CipherUtil.CIPHER_LOCK
import org.session.libsignal.utilities.ByteUtil
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.Util
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@WorkerThread
internal object AESGCM {
    internal val gcmTagSize = 128
    internal val ivSize = 12

    internal data class EncryptionResult(
        internal val ciphertext: ByteArray,
        internal val symmetricKey: ByteArray,
        internal val ephemeralPublicKey: ByteArray
    )

    /**
     * Sync. Don't call from the main thread.
     */
    internal fun decrypt(
        ivAndCiphertext: ByteArray,
        offset: Int = 0,
        len: Int = ivAndCiphertext.size,
        symmetricKey: ByteArray
    ): ByteArray {
        val iv = ivAndCiphertext.sliceArray(offset until (offset + ivSize))
        synchronized(CIPHER_LOCK) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(symmetricKey, "AES"), GCMParameterSpec(gcmTagSize, iv))
            return cipher.doFinal(ivAndCiphertext, offset + ivSize, len - ivSize)
        }
    }

    /**
     * Sync. Don't call from the main thread.
     */
    private fun generateSymmetricKey(x25519PublicKey: ByteArray, x25519PrivateKey: ByteArray): ByteArray {
        val ephemeralSharedSecret = SessionEncrypt.calculateECHDAgreement(x25519PubKey = x25519PublicKey, x25519PrivKey = x25519PrivateKey)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("LOKI".toByteArray(), "HmacSHA256"))
        return mac.doFinal(ephemeralSharedSecret)
    }

    /**
     * Sync. Don't call from the main thread.
     */
    internal fun encrypt(plaintext: ByteArray, symmetricKey: ByteArray): ByteArray {
        val iv = Util.getSecretBytes(ivSize)
        synchronized(CIPHER_LOCK) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(symmetricKey, "AES"), GCMParameterSpec(gcmTagSize, iv))
            return ByteUtil.combine(iv, cipher.doFinal(plaintext))
        }
    }

    /**
     * Sync. Don't call from the main thread.
     */
    internal fun encrypt(plaintext: ByteArray, hexEncodedX25519PublicKey: String): EncryptionResult {
        val x25519PublicKey = Hex.fromStringCondensed(hexEncodedX25519PublicKey)
        val ephemeralKeyPair = Curve25519.generateKeyPair()
        val symmetricKey = generateSymmetricKey(x25519PublicKey, ephemeralKeyPair.secretKey.data)
        val ciphertext = encrypt(plaintext, symmetricKey)
        return EncryptionResult(ciphertext, symmetricKey, ephemeralKeyPair.pubKey.data)
    }

}