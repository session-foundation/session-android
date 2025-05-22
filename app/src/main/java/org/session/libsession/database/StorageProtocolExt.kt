package org.session.libsession.database

import org.session.libsession.snode.OwnedSwarmAuth
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.toHexString

val StorageProtocol.userAuth: OwnedSwarmAuth?
    get() = getUserPublicKey()?.let { accountId ->
        getUserED25519KeyPair()?.let { keyPair ->
            OwnedSwarmAuth(
                accountId = AccountId(hexString = accountId),
                ed25519PublicKeyHex = keyPair.pubKey.data.toHexString(),
                ed25519PrivateKey = keyPair.secretKey.data
            )
        }
    }
