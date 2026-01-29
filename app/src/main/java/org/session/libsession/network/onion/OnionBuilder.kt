package org.session.libsession.network.onion

import org.session.libsession.network.model.OnionDestination
import org.session.libsession.network.model.Path
import org.session.libsignal.utilities.Snode
import javax.inject.Inject

open class OnionBuilder @Inject constructor(private val onionRequestEncryption: OnionRequestEncryption) {

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
        onionRequestVersion: OnionRequestVersion
    ): BuiltOnion {
        require(path.isNotEmpty()) { "Path must not be empty" }

        val destinationResult =
            onionRequestEncryption.encryptPayloadForDestination(payload, destination, onionRequestVersion)

        val encryptionResult = path.foldRight(
            destination to destinationResult
        ) { hop, (previousDestination, previousEncryptionResult) ->
            OnionDestination.SnodeDestination(hop) to onionRequestEncryption.encryptHop(
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

