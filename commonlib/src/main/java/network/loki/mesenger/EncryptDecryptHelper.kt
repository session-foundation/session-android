package network.loki.mesenger

import android.util.Base64
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.SecureRandom
import java.security.Security
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Clase unificada de cifrado/descifrado:
 *    1) Métodos CON password (PBKDF2) -> AES, DES, Camellia, ChaCha20, XChaCha20
 *    2) Métodos SIN password ("raw key") -> AES-GCM 32 bytes directos
 *
 * Formato general de cifrado con password (AES, Camellia, etc):
 *   [ salt(16) + iv(12) + ciphertext+tag ]  => Base64
 *
 * Formato “raw key” (solo AES-GCM de 32 bytes):
 *   [ iv(12) + ciphertext+tag ] => Base64
 */
object EncryptDecryptHelper {

    init {
        // Registrar BouncyCastle si no está ya presente
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    // =======================================================
    // ========== PARÁMETROS GLOBALES DE PBKDF2, etc. ========
    // =======================================================
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_SIZE_BITS = 256  // Clave de 256 bits
    private const val SALT_SIZE = 16       // Salt de 16 bytes
    private const val IV_SIZE_GCM = 12     // IV (nonce) de 12 bytes en AES GCM / Camellia GCM
    private const val GCM_TAG_BITS = 128   // Tag GCM de 128 bits
    // Para XChaCha20 => 24 bytes de nonce

    // =======================================================
    // ============ 1) AES "RAW KEY" (sin password) ==========
    // =======================================================

    /**
     * Genera 32 bytes aleatorios (256 bits) para usar como "raw key" en AES-GCM sin PBKDF2.
     */
    fun generateRawKey32(): ByteArray {
        val raw = ByteArray(32)
        SecureRandom().nextBytes(raw)
        return raw
    }

    /**
     * Cifra un texto con AES-GCM usando clave "raw" (32 bytes directos).
     * Retorna Base64( [IV(12) + cipherText+tag] ).
     */
    fun encryptAESWithRawKey(plainText: String, rawKeyBytes: ByteArray): String {
        val iv = ByteArray(IV_SIZE_GCM).also { SecureRandom().nextBytes(it) }
        val secretKey = SecretKeySpec(rawKeyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // [IV(12) + cipherBytes(con tag)]
        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Descifra un texto con AES-GCM "raw key" ([IV + ciphertext+tag] en Base64).
     */
    fun decryptAESWithRawKey(base64Cipher: String, rawKeyBytes: ByteArray): String {
        return try {
            val allBytes = Base64.decode(base64Cipher, Base64.NO_WRAP)
            if (allBytes.size < IV_SIZE_GCM) {
                return "Error: datos inválidos o muy cortos."
            }

            val iv = allBytes.copyOfRange(0, IV_SIZE_GCM)
            val cipherBytes = allBytes.copyOfRange(IV_SIZE_GCM, allBytes.size)
            val secretKey = SecretKeySpec(rawKeyBytes, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)

        } catch (e: AEADBadTagException) {
            "Error: autenticidad no válida o clave incorrecta."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // =======================================================
    // ============ 2) AES (GCM) con PASSWORD (PBKDF2) =======
    // =======================================================
    fun encryptAES(plainText: String, password: String): String {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val secretKey = deriveKeyPBKDF2(password, salt, KEY_SIZE_BITS, "AES")

        val iv = ByteArray(IV_SIZE_GCM).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // [ salt(16) + iv(12) + cipherBytes ]
        val combined = ByteArray(salt.size + iv.size + cipherBytes.size)
        System.arraycopy(salt, 0, combined, 0, salt.size)
        System.arraycopy(iv, 0, combined, salt.size, iv.size)
        System.arraycopy(cipherBytes, 0, combined, salt.size + iv.size, cipherBytes.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptAES(base64Cipher: String, password: String): String {
        return try {
            val allBytes = Base64.decode(base64Cipher, Base64.NO_WRAP)
            if (allBytes.size < SALT_SIZE + IV_SIZE_GCM) {
                return "Error: datos inválidos para AES."
            }
            val salt = allBytes.copyOfRange(0, SALT_SIZE)
            val iv = allBytes.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE_GCM)
            val cipherBytes = allBytes.copyOfRange(SALT_SIZE + IV_SIZE_GCM, allBytes.size)

            val secretKey = deriveKeyPBKDF2(password, salt, KEY_SIZE_BITS, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)

        } catch (e: AEADBadTagException) {
            "Error: autenticidad no válida o contraseña incorrecta."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // =======================================================
    // ============ 3) CAMELLIA (GCM) con PASSWORD ===========
    // =======================================================
    fun encryptCamellia(plainText: String, password: String): String {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val keyBytes = deriveKeyBytes(password, salt, KEY_SIZE_BITS)
        val iv = ByteArray(IV_SIZE_GCM).also { SecureRandom().nextBytes(it) }

        val gcm = GCMBlockCipher(org.bouncycastle.crypto.engines.CamelliaEngine())
        val aeadParams = AEADParameters(KeyParameter(keyBytes), GCM_TAG_BITS, iv)
        gcm.init(true, aeadParams)

        val input = plainText.toByteArray(Charsets.UTF_8)
        val outBuf = ByteArray(gcm.getOutputSize(input.size))
        val len1 = gcm.processBytes(input, 0, input.size, outBuf, 0)
        val len2 = gcm.doFinal(outBuf, len1)
        val cipherBytes = outBuf.copyOfRange(0, len1 + len2)

        val combined = ByteArray(salt.size + iv.size + cipherBytes.size)
        System.arraycopy(salt, 0, combined, 0, salt.size)
        System.arraycopy(iv, 0, combined, salt.size, iv.size)
        System.arraycopy(cipherBytes, 0, combined, salt.size + iv.size, cipherBytes.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptCamellia(base64Cipher: String, password: String): String {
        return try {
            val allBytes = Base64.decode(base64Cipher, Base64.NO_WRAP)
            if (allBytes.size < SALT_SIZE + IV_SIZE_GCM) {
                return "Error: datos inválidos para Camellia."
            }
            val salt = allBytes.copyOfRange(0, SALT_SIZE)
            val iv = allBytes.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE_GCM)
            val cipherData = allBytes.copyOfRange(SALT_SIZE + IV_SIZE_GCM, allBytes.size)

            val keyBytes = deriveKeyBytes(password, salt, KEY_SIZE_BITS)
            val gcm = GCMBlockCipher(org.bouncycastle.crypto.engines.CamelliaEngine())
            val aeadParams = AEADParameters(KeyParameter(keyBytes), GCM_TAG_BITS, iv)
            gcm.init(false, aeadParams)

            val outBuf = ByteArray(gcm.getOutputSize(cipherData.size))
            val len1 = gcm.processBytes(cipherData, 0, cipherData.size, outBuf, 0)
            val len2 = gcm.doFinal(outBuf, len1)
            val plainBytes = outBuf.copyOfRange(0, len1 + len2)
            String(plainBytes, Charsets.UTF_8)

        } catch (e: InvalidCipherTextException) {
            "Error: autenticidad no válida o contraseña incorrecta."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // =======================================================
    // ====== 4) ChaCha20-Poly1305 (API) con PASSWORD ========
    // =======================================================
    fun encryptChaCha20Poly1305(plainText: String, password: String): String {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val secretKey = deriveKeyPBKDF2(password, salt, KEY_SIZE_BITS, "ChaCha20")

        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        val spec = GCMParameterSpec(GCM_TAG_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(salt.size + nonce.size + cipherBytes.size)
        System.arraycopy(salt, 0, combined, 0, salt.size)
        System.arraycopy(nonce, 0, combined, salt.size, nonce.size)
        System.arraycopy(cipherBytes, 0, combined, salt.size + nonce.size, cipherBytes.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptChaCha20Poly1305(base64Cipher: String, password: String): String {
        return try {
            val allBytes = Base64.decode(base64Cipher, Base64.NO_WRAP)
            if (allBytes.size < SALT_SIZE + 12) {
                return "Error: datos inválidos para ChaCha20Poly1305."
            }
            val salt = allBytes.copyOfRange(0, SALT_SIZE)
            val nonce = allBytes.copyOfRange(SALT_SIZE, SALT_SIZE + 12)
            val cipherBytes = allBytes.copyOfRange(SALT_SIZE + 12, allBytes.size)

            val secretKey = deriveKeyPBKDF2(password, salt, KEY_SIZE_BITS, "ChaCha20")
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val spec = GCMParameterSpec(GCM_TAG_BITS, nonce)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)

        } catch (e: AEADBadTagException) {
            "Error: autenticidad no válida o contraseña incorrecta."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // =======================================================
    // ====== 5) DES (ECB/PKCS5) con “password” (pero simple) =
    // =======================================================
    fun encryptDES(plainText: String, key: String): String {
        val secretKey = generateDESKey(key)
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
    }

    fun decryptDES(cipherText: String, key: String): String {
        return try {
            val secretKey = generateDESKey(key)
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decryptedBytes = cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP))
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun generateDESKey(key: String): SecretKeySpec {
        val keyBytes = key.toByteArray(Charsets.UTF_8).copyOf(8)
        return SecretKeySpec(keyBytes, "DES")
    }

    // =======================================================
    // ==== 6) XChaCha20-Poly1305 (Lightweight) con password =
    // =======================================================
    fun encryptXChaCha20Poly1305(plainText: String, password: String): String {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val keyBytes = deriveKeyBytes(password, salt, KEY_SIZE_BITS)

        val nonce24 = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val aad = ByteArray(0)
        val cipherBytes = XChaCha20Poly1305.encrypt(keyBytes, nonce24, aad, plainText.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(salt.size + nonce24.size + cipherBytes.size)
        System.arraycopy(salt, 0, combined, 0, salt.size)
        System.arraycopy(nonce24, 0, combined, salt.size, nonce24.size)
        System.arraycopy(cipherBytes, 0, combined, salt.size + nonce24.size, cipherBytes.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptXChaCha20Poly1305(base64Cipher: String, password: String): String {
        val allBytes = Base64.decode(base64Cipher, Base64.NO_WRAP)
        if (allBytes.size < SALT_SIZE + 24) {
            return "Error: datos inválidos para XChaCha20Poly1305."
        }
        val salt = allBytes.copyOfRange(0, SALT_SIZE)
        val nonce24 = allBytes.copyOfRange(SALT_SIZE, SALT_SIZE + 24)
        val cipherBytes = allBytes.copyOfRange(SALT_SIZE + 24, allBytes.size)

        val keyBytes = deriveKeyBytes(password, salt, KEY_SIZE_BITS)
        val aad = ByteArray(0)
        return try {
            val plainBytes = XChaCha20Poly1305.decrypt(keyBytes, nonce24, aad, cipherBytes)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: InvalidCipherTextException) {
            "Error: autenticidad no válida o contraseña incorrecta."
        }
    }

    // =======================================================
    // ========== Derivación de clave (PBKDF2) ===============
    // =======================================================
    private fun deriveKeyPBKDF2(
        password: String,
        salt: ByteArray,
        keySizeBits: Int,
        algorithmForKeySpec: String
    ): SecretKeySpec {
        val keyBytes = deriveKeyBytes(password, salt, keySizeBits)
        return SecretKeySpec(keyBytes, algorithmForKeySpec)
    }

    fun deriveKeyBytes(password: String, salt: ByteArray, keySizeBits: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, keySizeBits)
        val tmp = factory.generateSecret(spec)
        return tmp.encoded
    }
}
