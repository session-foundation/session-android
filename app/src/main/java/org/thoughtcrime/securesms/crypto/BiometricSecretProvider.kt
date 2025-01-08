package org.thoughtcrime.securesms.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.Util
import java.security.InvalidKeyException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.UnrecoverableKeyException

class BiometricSecretProvider {

    companion object {
        private const val BIOMETRIC_ASYM_KEY_ALIAS = "Session-biometric-asym"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val SIGNATURE_ALGORITHM = "SHA512withECDSA"
    }

    fun getRandomData() = Util.getSecretBytes(32)

    private fun createAsymmetricKey() {
        val keyGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(BIOMETRIC_ASYM_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA512
            )
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationValidityDurationSeconds(-1)

        // Unlocked device required for enhanced security on Android P+ (e.g., API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }

        // If biometrics are removed, keys become invalid
        builder.setInvalidatedByBiometricEnrollment(true)

        keyGenerator.initialize(builder.build())
        keyGenerator.generateKeyPair()
    }

    /**
     * Returns a Signature object initialized for signing with a private key protected by biometrics.
     *
     * If no biometrics are enrolled, returns null instead of throwing an exception. The caller should
     * handle this scenario by falling back to device credentials or skipping biometric-based logic.
     */
    fun getOrCreateBiometricSignature(context: Context): Signature? {
        // Check if biometrics are available and enrolled
        val biometricManager = BiometricManager.from(context)
        val canAuthenticate = biometricManager.canAuthenticate(BIOMETRIC_STRONG)
        if (canAuthenticate != BIOMETRIC_SUCCESS) {
            // No biometrics enrolled or hardware unavailable; return null to signal fallback
            return null
        }

        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)

        // Check if the key already exists and if we've flagged that it's been generated
        if (!ks.containsAlias(BIOMETRIC_ASYM_KEY_ALIAS) ||
            !ks.entryInstanceOf(BIOMETRIC_ASYM_KEY_ALIAS, KeyStore.PrivateKeyEntry::class.java) ||
            !TextSecurePreferences.getFingerprintKeyGenerated(context)
        ) {
            // Create the key if it doesn't exist or isn't properly tracked
            try {
                createAsymmetricKey()
                TextSecurePreferences.setFingerprintKeyGenerated(context)
            } catch (e: Exception) {
                // If key generation fails (e.g. due to no biometrics), return null
                return null
            }
        }

        // Attempt to initialize the signature with the private key
        return try {
            val key = ks.getKey(BIOMETRIC_ASYM_KEY_ALIAS, null) as PrivateKey
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
            signature.initSign(key)
            signature
        } catch (e: InvalidKeyException) {
            // If the key is invalid for some reason, recreate it
            ks.deleteEntry(BIOMETRIC_ASYM_KEY_ALIAS)
            return try {
                createAsymmetricKey()
                TextSecurePreferences.setFingerprintKeyGenerated(context)
                val key = ks.getKey(BIOMETRIC_ASYM_KEY_ALIAS, null) as PrivateKey
                val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
                signature.initSign(key)
                signature
            } catch (ex: Exception) {
                // If key creation fails again, return null
                null
            }
        } catch (e: UnrecoverableKeyException) {
            // Handle key being unrecoverable (rare scenario)
            ks.deleteEntry(BIOMETRIC_ASYM_KEY_ALIAS)
            return null
        }
    }

    fun verifySignature(data: ByteArray, signedData: ByteArray): Boolean {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        val certificate = ks.getCertificate(BIOMETRIC_ASYM_KEY_ALIAS) ?: return false
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initVerify(certificate)
        signature.update(data)
        return signature.verify(signedData)
    }
}