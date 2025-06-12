package org.session.libsession.snode

import network.loki.messenger.libsession_util.ED25519
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64

/**
 * A [SwarmAuth] that signs message using a single ED25519 private key.
 *
 * This should be used for the owner of an account, like a user or a group admin.
 */
class OwnedSwarmAuth(
    override val accountId: AccountId,
    override val ed25519PublicKeyHex: String?,
    val ed25519PrivateKey: ByteArray,
) : SwarmAuth {
    override fun sign(data: ByteArray): Map<String, String> {
        val signature = Base64.encodeBytes(ED25519.sign(ed25519PrivateKey = ed25519PrivateKey, message = data))

        return buildMap {
            put("signature", signature)
        }
    }

    override fun signForPushRegistry(data: ByteArray): Map<String, String> {
        return sign(data)
    }

    companion object {
        fun ofClosedGroup(groupAccountId: AccountId, adminKey: ByteArray): OwnedSwarmAuth {
            return OwnedSwarmAuth(
                accountId = groupAccountId,
                ed25519PublicKeyHex = null,
                ed25519PrivateKey = adminKey
            )
        }
    }
}