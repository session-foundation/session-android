package org.session.libsession.messaging.sending_receiving

import network.loki.messenger.libsession_util.SessionEncrypt
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.sending_receiving.MessageSender.Error
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.removingIdPrefixIfNeeded

object MessageEncrypter {

    /**
     * Encrypts `plaintext` using the Session protocol for `hexEncodedX25519PublicKey`.
     *
     * @param plaintext the plaintext to encrypt. Must already be padded.
     * @param recipientHexEncodedX25519PublicKey the X25519 public key to encrypt for. Could be the Account ID of a user, or the public key of a closed group.
     *
     * @return the encrypted message.
     */
    internal fun encrypt(plaintext: ByteArray, recipientHexEncodedX25519PublicKey: String): ByteArray {
        val userED25519KeyPair = MessagingModuleConfiguration.shared.storage.getUserED25519KeyPair() ?: throw Error.NoUserED25519KeyPair
        val recipientX25519PublicKey = Hex.fromStringCondensed(recipientHexEncodedX25519PublicKey.removingIdPrefixIfNeeded())

        try {
            return SessionEncrypt.encryptForRecipient(
                userED25519KeyPair.secretKey.data,
                recipientX25519PublicKey,
                plaintext
            ).data
        } catch (exception: Exception) {
            Log.d("Loki", "Couldn't encrypt message due to error: $exception.")
            throw Error.EncryptionFailed
        }
    }

    internal fun encryptBlinded(
        plaintext: ByteArray,
        recipientBlindedId: String,
        serverPublicKey: String
    ): ByteArray {
        if (IdPrefix.fromValue(recipientBlindedId) != IdPrefix.BLINDED) throw Error.SigningFailed
        val userEdKeyPair =
            MessagingModuleConfiguration.shared.storage.getUserED25519KeyPair() ?: throw Error.NoUserED25519KeyPair
        val recipientBlindedPublicKey = Hex.fromStringCondensed(recipientBlindedId.removingIdPrefixIfNeeded())

        return SessionEncrypt.encryptForBlindedRecipient(
            message = plaintext,
            myEd25519Privkey = userEdKeyPair.secretKey.data,
            serverPubKey = Hex.fromStringCondensed(serverPublicKey),
            recipientBlindId = byteArrayOf(0x15) + recipientBlindedPublicKey
        ).data
    }

}