package org.thoughtcrime.securesms.util

import network.loki.messenger.libsession_util.ReadableConversationVolatileConfig
import org.session.libsession.utilities.Address


fun ReadableConversationVolatileConfig.getConversationUnread(recipientAddress: Address.Conversable): Boolean {
    return when (recipientAddress) {
        is Address.Standard -> {
            getOneToOne(recipientAddress.accountId.hexString)?.unread == true
        }

        is Address.Group -> {
            getClosedGroup(recipientAddress.accountId.hexString)?.unread == true
        }

        is Address.LegacyGroup -> {
            getLegacyClosedGroup(recipientAddress.groupPublicKeyHex)?.unread == true
        }

        is Address.Community -> {
            getCommunity(baseUrl = recipientAddress.serverUrl, room = recipientAddress.room)?.unread == true
        }

        is Address.CommunityBlindedId -> false
    }
}
