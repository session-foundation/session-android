package network.loki.mesenger

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Helper para cifrar/descifrar binario con AES-GCM usando una clave cruda (raw key) de 32 bytes,
 * sin PBKDF2, sin volver a codificar en Base64. Formato: [IV(12 bytes) + ciphertext + tag].
 */
object RawKeyEncryptDecryptHelper {

    private const val IV_SIZE_GCM = 12       // 12 bytes de IV (96 bits) para AES-GCM
    private const val GCM_TAG_BITS = 128     // Tag de 128 bits (16 bytes)

    init {
        // Aseguramos BouncyCastle como proveedor (si no existe ya)
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Genera 32 bytes (256 bits) de forma aleatoria para usarlos como 'raw key' en AES-GCM.
     */
    fun generateRawKey32(): ByteArray {
        val keyBytes = ByteArray(32) // 256 bits
        SecureRandom().nextBytes(keyBytes)
        return keyBytes
    }

    /**
     * Cifra en binario (AES-GCM) usando 'rawKeyBytes' de 32 bytes.
     * El resultado devuelto es un array con: [IV(12) + ciphertext + tag].
     */
    fun encryptBytesAESWithRawKey(plainData: ByteArray, rawKeyBytes: ByteArray): ByteArray {
        val iv = ByteArray(IV_SIZE_GCM).also { SecureRandom().nextBytes(it) }
        val secretKey = SecretKeySpec(rawKeyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding", "BC")
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val cipherBytes = cipher.doFinal(plainData)
        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)

        return combined
    }

    /**
     * Descifra en binario (AES-GCM). Recibe un array en el formato: [IV(12) + ciphertext + tag].
     * @throws IllegalArgumentException si el array es muy corto para contener el IV.
     */
    fun decryptBytesAESWithRawKey(encryptedData: ByteArray, rawKeyBytes: ByteArray): ByteArray {
        if (encryptedData.size < IV_SIZE_GCM) {
            throw IllegalArgumentException("Invalid data for AES-GCM raw key.")
        }
        val iv = encryptedData.copyOfRange(0, IV_SIZE_GCM)
        val cipherBytes = encryptedData.copyOfRange(IV_SIZE_GCM, encryptedData.size)
        val secretKey = SecretKeySpec(rawKeyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding", "BC")
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return cipher.doFinal(cipherBytes)
    }
}
