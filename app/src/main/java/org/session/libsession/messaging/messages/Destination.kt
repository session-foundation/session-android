package org.session.libsession.messaging.messages

import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.utilities.Address

sealed class Destination {

    data class Contact(var publicKey: String) : Destination() {
        internal constructor(): this("")
    }
    data class LegacyClosedGroup(var groupPublicKey: String) : Destination() {
        internal constructor(): this("")
    }
    data class LegacyOpenGroup(var roomToken: String, var server: String) : Destination() {
        internal constructor(): this("", "")
    }
    data class ClosedGroup(var publicKey: String): Destination() {
        internal constructor(): this("")
    }

    class OpenGroup(
        var roomToken: String = "",
        var server: String = "",
        var whisperTo: List<String> = emptyList(),
        var whisperMods: Boolean = false,
        var fileIds: List<String> = emptyList()
    ) : Destination()

    class OpenGroupInbox(
        var server: String,
        var serverPublicKey: String,
        var blindedPublicKey: String
    ) : Destination()

    companion object {

        fun from(address: Address, fileIds: List<String> = emptyList()): Destination {
            return when (address) {
                is Address.Standard -> {
                    Contact(address.address)
                }
                is Address.LegacyGroup -> {
                    LegacyClosedGroup(address.groupPublicKeyHex)
                }
                is Address.Community -> {
                    val storage = MessagingModuleConfiguration.shared.storage
                    val threadID = storage.getThreadId(address)!!
                    storage.getOpenGroup(threadID)?.let {
                        OpenGroup(roomToken = it.room, server = it.server, fileIds = fileIds)
                    } ?: throw Exception("Missing open group for thread with ID: $threadID.")
                }
                is Address.CommunityBlindedId -> {
                    val serverPublicKey = MessagingModuleConfiguration.shared.configFactory
                        .withUserConfigs { configs ->
                            configs.userGroups.allCommunityInfo()
                                .first { it.community.baseUrl == address.serverUrl.toString() }
                                .community
                                .pubKeyHex
                        }

                    OpenGroupInbox(
                        server = address.serverUrl.toString(),
                        serverPublicKey = serverPublicKey,
                        blindedPublicKey = address.blindedId.blindedId.hexString,
                    )
                }
                is Address.Group -> {
                    ClosedGroup(address.accountId.hexString)
                }
                else -> {
                    throw Exception("TODO: Handle legacy closed groups.")
                }
            }
        }
    }
}