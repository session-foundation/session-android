package org.session.libsession.messaging.sending_receiving

import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign
import network.loki.messenger.libsession_util.SessionEncrypt
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.sending_receiving.MessageReceiver.Error
import org.session.libsession.messaging.utilities.SodiumUtilities
import org.session.libsession.messaging.utilities.SodiumUtilities.sodium
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.hexEncodedPublicKey
import org.session.libsignal.utilities.removingIdPrefixIfNeeded

object MessageDecrypter {

    /**
     * Decrypts `ciphertext` using the Session protocol and `x25519KeyPair`.
     *
     * @param ciphertext the data to decrypt.
     * @param x25519KeyPair the key pair to use for decryption. This could be the current user's key pair, or the key pair of a closed group.
     *
     * @return the padded plaintext.
     */
    public fun decrypt(ciphertext: ByteArray, x25519KeyPair: ECKeyPair): Pair<ByteArray, String> {
        val recipientX25519PrivateKey = x25519KeyPair.privateKey.serialize()
        val recipientX25519PublicKey = Hex.fromStringCondensed(x25519KeyPair.hexEncodedPublicKey.removingIdPrefixIfNeeded())
        val signatureSize = Sign.BYTES
        val ed25519PublicKeySize = Sign.PUBLICKEYBYTES

        // 1. ) Decrypt the message
        val plaintextWithMetadata = ByteArray(ciphertext.size - Box.SEALBYTES)
        try {
            sodium.cryptoBoxSealOpen(plaintextWithMetadata, ciphertext, ciphertext.size.toLong(), recipientX25519PublicKey, recipientX25519PrivateKey)
        } catch (exception: Exception) {
            Log.d("Loki", "Couldn't decrypt message due to error: $exception.")
            throw Error.DecryptionFailed
        }
        if (plaintextWithMetadata.size <= (signatureSize + ed25519PublicKeySize)) { throw Error.DecryptionFailed }
        // 2. ) Get the message parts
        val signature = plaintextWithMetadata.sliceArray(plaintextWithMetadata.size - signatureSize until plaintextWithMetadata.size)
        val senderED25519PublicKey = plaintextWithMetadata.sliceArray(plaintextWithMetadata.size - (signatureSize + ed25519PublicKeySize) until plaintextWithMetadata.size - signatureSize)
        val plaintext = plaintextWithMetadata.sliceArray(0 until plaintextWithMetadata.size - (signatureSize + ed25519PublicKeySize))
        // 3. ) Verify the signature
        val verificationData = (plaintext + senderED25519PublicKey + recipientX25519PublicKey)
        try {
            val isValid = sodium.cryptoSignVerifyDetached(signature, verificationData, verificationData.size, senderED25519PublicKey)
            if (!isValid) { throw Error.InvalidSignature }
        } catch (exception: Exception) {
            Log.d("Loki", "Couldn't verify message signature due to error: $exception.")
            throw Error.InvalidSignature
        }
        // 4. ) Get the sender's X25519 public key
        val senderX25519PublicKey = ByteArray(Sign.CURVE25519_PUBLICKEYBYTES)
        sodium.convertPublicKeyEd25519ToCurve25519(senderX25519PublicKey, senderED25519PublicKey)

        val id = AccountId(IdPrefix.STANDARD, senderX25519PublicKey)
        return Pair(plaintext, id.hexString)
    }

    fun decryptBlinded(
        message: ByteArray,
        isOutgoing: Boolean,
        otherBlindedPublicKey: String,
        serverPublicKey: String
    ): Pair<ByteArray, String> {
        val userEdKeyPair = MessagingModuleConfiguration.shared.storage.getUserED25519KeyPair() ?: throw Error.NoUserED25519KeyPair
        val blindedKeyPair = SodiumUtilities.blindedKeyPair(serverPublicKey, userEdKeyPair) ?: throw Error.DecryptionFailed
        val otherKeyBytes = Hex.fromStringCondensed(otherBlindedPublicKey.removingIdPrefixIfNeeded())

        val senderKeyBytes: ByteArray
        val recipientKeyBytes: ByteArray

        if (isOutgoing) {
            senderKeyBytes = blindedKeyPair.publicKey.asBytes
            recipientKeyBytes = otherKeyBytes
        } else {
            senderKeyBytes = otherKeyBytes
            recipientKeyBytes = blindedKeyPair.publicKey.asBytes
        }

        try {
            val (sessionId, plainText) = SessionEncrypt.decryptForBlindedRecipient(
                ciphertext = message,
                myEd25519Privkey = userEdKeyPair.secretKey.asBytes,
                openGroupPubkey = Hex.fromStringCondensed(serverPublicKey),
                senderBlindedId = byteArrayOf(0x15) + senderKeyBytes,
                recipientBlindId = byteArrayOf(0x15) + recipientKeyBytes,
            )

            return plainText.data to sessionId
        } catch (e: Exception) {
            Log.e("MessageDecrypter", "Failed to decrypt blinded message", e)
            throw Error.DecryptionFailed
        }
    }
}