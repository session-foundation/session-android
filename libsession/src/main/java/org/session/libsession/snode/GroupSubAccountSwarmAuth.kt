package org.session.libsession.snode

import network.loki.messenger.libsession_util.GroupKeysConfig
import org.session.libsignal.utilities.AccountId

/**
 * A [SwarmAuth] that signs message using a group's subaccount. This should be used for non-admin
 * users of a group signing their messages.
 */
class GroupSubAccountSwarmAuth(
    private val groupKeysConfig: GroupKeysConfig,
    override val accountId: AccountId,
    private val authData: ByteArray
) : SwarmAuth {
    override val ed25519PublicKeyHex: String? get() = null

    init {
        check(authData.size == 100) {
            "Invalid auth data size, expecting 100 but got ${authData.size}"
        }
    }

    override fun sign(data: ByteArray): Map<String, String> {
        val auth = groupKeysConfig.subAccountSign(data, authData)
        return buildMap {
            put("subaccount", auth.subAccount)
            put("subaccount_sig", auth.subAccountSig)
            put("signature", auth.signature)
        }
    }

    override fun signForPushRegistry(data: ByteArray): Map<String, String> {
        val auth = groupKeysConfig.subAccountSign(data, authData)
        return buildMap {
            put("subkey_tag", auth.subAccount)
            put("signature", auth.signature)
        }
    }
}