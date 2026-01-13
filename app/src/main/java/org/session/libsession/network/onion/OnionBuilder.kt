package org.session.libsession.network.onion

import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.Path
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

        val destinationResult =
            OnionRequestEncryption.encryptPayloadForDestination(payload, destination, version)

        val encryptionResult = path.foldRight(
            destination to destinationResult
        ) { hop, (previousDestination, previousEncryptionResult) ->
            OnionDestination.SnodeDestination(hop) to OnionRequestEncryption.encryptHop(
                lhs = OnionDestination.SnodeDestination(hop),
                rhs = previousDestination,
                previousEncryptionResult = previousEncryptionResult,
            )
        }.second

        return BuiltOnion(
            guard = path.first(),
            ciphertext = encryptionResult.ciphertext,
            ephemeralPublicKey = encryptionResult.ephemeralPublicKey,
            destinationSymmetricKey = destinationResult.symmetricKey
        )
    }
}

