package org.session.libsession.messaging.messages

import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.GroupUtil
import org.session.libsignal.utilities.toHexString

sealed class Destination {

    data class Contact(var publicKey: String) : Destination() {
        internal constructor(): this("")
    }
    data class ClosedGroup(var groupPublicKey: String) : Destination() {
        internal constructor(): this("")
    }
    data class LegacyOpenGroup(var roomToken: String, var server: String) : Destination() {
        internal constructor(): this("", "")
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
            return when {
                address.isContact -> {
                    Contact(address.contactIdentifier())
                }
                address.isClosedGroup -> {
                    val groupID = address.toGroupString()
                    val groupPublicKey = GroupUtil.doubleDecodeGroupID(groupID).toHexString()
                    ClosedGroup(groupPublicKey)
                }
                address.isCommunity -> {
                    val storage = MessagingModuleConfiguration.shared.storage
                    val threadID = storage.getThreadId(address)!!
                    storage.getOpenGroup(threadID)?.let {
                        OpenGroup(roomToken = it.room, server = it.server, fileIds = fileIds)
                    } ?: throw Exception("Missing open group for thread with ID: $threadID.")
                }
                address.isCommunityInbox -> {
                    val groupInboxId = GroupUtil.getDecodedGroupID(address.serialize()).split("!")
                    OpenGroupInbox(
                        groupInboxId.dropLast(2).joinToString("!"),
                        groupInboxId.dropLast(1).last(),
                        groupInboxId.last()
                    )
                }
                else -> {
                    throw Exception("TODO: Handle legacy closed groups.")
                }
            }
        }
    }
}