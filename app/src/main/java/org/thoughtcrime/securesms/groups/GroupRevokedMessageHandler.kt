package org.thoughtcrime.securesms.groups

import network.loki.messenger.libsession_util.util.MultiEncrypt
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import javax.inject.Inject
import javax.inject.Provider

class GroupRevokedMessageHandler @Inject constructor(
    private val configFactoryProtocol: ConfigFactoryProtocol,
    private val storage: StorageProtocol,
    private val groupManagerV2: Provider<GroupManagerV2>,
) {
    suspend fun handleRevokeMessage(
        groupId: AccountId,
        rawMessages: List<ByteArray>,
    ) {
        rawMessages.forEach { data ->
            val decoded = configFactoryProtocol.decryptForUser(
                data,
                MultiEncrypt.KICKED_DOMAIN,
                groupId,
            )

            if (decoded != null) {
                // The message should be in the format of "<sessionIdPubKeyBinary><messageGenerationASCII>",
                // where the pub key is 32 bytes, so we need to have at least 33 bytes of data
                if (decoded.size < 33) {
                    Log.w(TAG, "Received an invalid kicked message, expecting at least 33 bytes, got ${decoded.size}")
                    return@forEach
                }

                val sessionId = AccountId(IdPrefix.STANDARD, decoded.copyOfRange(0, 32)) // copyOfRange: [start,end)
                val messageGeneration = decoded.copyOfRange(32, decoded.size).decodeToString().toIntOrNull()
                if (messageGeneration == null) {
                    Log.w(TAG, "Received an invalid kicked message: missing message generation")
                    return@forEach
                }

                val currentKeysGeneration = configFactoryProtocol.withGroupConfigs(groupId) {
                    it.groupKeys.currentGeneration()
                }

                val isForMe = sessionId.hexString == storage.getUserPublicKey()
                Log.d(TAG, "Received kicked message, for us? ${isForMe}, message key generation = $messageGeneration, our key generation = $currentKeysGeneration")

                if (isForMe && messageGeneration >= currentKeysGeneration) {
                    groupManagerV2.get().handleKicked(groupId)
                }
            }
        }
    }

    companion object {
        private const val TAG = "GroupRevokedMessageHandler"
    }
}