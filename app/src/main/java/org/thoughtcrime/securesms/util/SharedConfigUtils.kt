package org.thoughtcrime.securesms.util

import network.loki.messenger.libsession_util.MutableConversationVolatileConfig
import network.loki.messenger.libsession_util.ReadableConversationVolatileConfig
import network.loki.messenger.libsession_util.util.Conversation
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.MutableUserConfigs

fun ReadableConversationVolatileConfig.get(address: Address.Conversable): Conversation? {
    return when (address) {
        is Address.Standard -> {
            getOneToOne(address.accountId.hexString)
        }

        is Address.Group -> {
            getClosedGroup(address.accountId.hexString)
        }

        is Address.LegacyGroup -> {
            getLegacyClosedGroup(address.groupPublicKeyHex)
        }

        is Address.Community -> {
            getCommunity(baseUrl = address.serverUrl, room = address.room)
        }

        is Address.CommunityBlindedId -> {
            getBlindedOneToOne(address.blindedId.address)
        }
    }
}

fun MutableConversationVolatileConfig.erase(address: Address.Conversable) {
    when (address) {
        is Address.Standard -> {
            eraseOneToOne(address.accountId.hexString)
        }

        is Address.Group -> {
            eraseClosedGroup(address.accountId.hexString)
        }

        is Address.LegacyGroup -> {
            eraseLegacyClosedGroup(address.groupPublicKeyHex)
        }

        is Address.Community -> {
            eraseCommunity(baseUrl = address.serverUrl, room = address.room)
        }

        is Address.CommunityBlindedId -> {
            eraseBlindedOneToOne(address.blindedId.address)
        }
    }
}

fun MutableUserConfigs.getOrConstructConvo(address: Address.Conversable): Conversation {
    return when (address) {
        is Address.Standard -> {
            convoInfoVolatile.getOrConstructOneToOne(address.accountId.hexString)
        }

        is Address.Group -> {
            convoInfoVolatile.getOrConstructClosedGroup(address.accountId.hexString)
        }

        is Address.LegacyGroup -> {
            convoInfoVolatile.getOrConstructLegacyGroup(address.groupPublicKeyHex)
        }

        is Address.Community -> {
            val community = requireNotNull(
                userGroups.getCommunityInfo(
                    baseUrl = address.serverUrl,
                    room = address.room
                )
            ) { "Community does not exist" }

            convoInfoVolatile.getOrConstructCommunity(
                baseUrl = address.serverUrl,
                room = address.room,
                pubKeyHex = community.community.pubKeyHex
            )
        }

        is Address.CommunityBlindedId -> {
            convoInfoVolatile.getOrConstructedBlindedOneToOne(address.blindedId.address)
        }
    }
}