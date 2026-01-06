package org.session.libsession.network.onion

import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.Path
import org.session.libsession.utilities.AESGCM.EncryptionResult
import org.session.libsignal.utilities.Snode

object OnionBuilder {

    data class BuiltOnion(
        val guard: Snode,
        val ciphertext: ByteArray,
        val ephemeralPublicKey: ByteArray,
        val destinationSymmetricKey: ByteArray
    )

    fun build(
        path: Path,
        destination: OnionDestination,
        payload: ByteArray,
        version: Version
    ): BuiltOnion {
        require(path.isNotEmpty()) { "Path must not be empty" }

        val guardSnode = path.first()

        val destResult: EncryptionResult =
            OnionRequestEncryption.encryptPayloadForDestination(payload, destination, version)

        var encryptionResult: EncryptionResult = destResult
        var rhs: OnionDestination = destination
        var remainingPath = path

        fun addLayer(): EncryptionResult {
            return if (remainingPath.isEmpty()) {
                encryptionResult
            } else {
                val lhs = OnionDestination.SnodeDestination(remainingPath.last())
                remainingPath = remainingPath.dropLast(1)

                OnionRequestEncryption.encryptHop(lhs, rhs, encryptionResult).also { r ->
                    encryptionResult = r
                    rhs = lhs
                }
            }
        }

        while (remainingPath.isNotEmpty()) {
            addLayer()
        }

        return BuiltOnion(
            guard = guardSnode,
            ciphertext = encryptionResult.ciphertext,
            ephemeralPublicKey = encryptionResult.ephemeralPublicKey,
            destinationSymmetricKey = destResult.symmetricKey
        )
    }
}

