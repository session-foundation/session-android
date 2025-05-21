package org.session.libsession.messaging.sending_receiving

import network.loki.messenger.libsession_util.SessionEncrypt
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.sending_receiving.MessageReceiver.Error
import org.session.libsession.messaging.utilities.SodiumUtilities
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.utilities.Hex
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
    fun decrypt(ciphertext: ByteArray, x25519KeyPair: ECKeyPair): Pair<ByteArray, String> {
        val recipientX25519PrivateKey = x25519KeyPair.privateKey.serialize()
        val recipientX25519PublicKey = Hex.fromStringCondensed(x25519KeyPair.hexEncodedPublicKey.removingIdPrefixIfNeeded())
        val (id, data) = SessionEncrypt.decryptIncoming(
            x25519PubKey = recipientX25519PublicKey,
            x25519PrivKey = recipientX25519PrivateKey,
            ciphertext = ciphertext
        )

        return data.data to id
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