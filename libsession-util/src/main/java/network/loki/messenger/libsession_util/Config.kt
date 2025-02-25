package network.loki.messenger.libsession_util

import network.loki.messenger.libsession_util.util.BaseCommunityInfo
import network.loki.messenger.libsession_util.util.ConfigPush
import network.loki.messenger.libsession_util.util.Contact
import network.loki.messenger.libsession_util.util.Conversation
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.GroupInfo
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsignal.protos.SignalServiceProtos.SharedConfigMessage.Kind
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import java.util.Stack


sealed class ConfigBase(protected val /* yucky */ pointer: Long) {
    companion object {
        init {
            System.loadLibrary("session_util")
        }
        external fun kindFor(configNamespace: Int): Class<ConfigBase>

        fun ConfigBase.protoKindFor(): Kind = when (this) {
            is UserProfile -> Kind.USER_PROFILE
            is Contacts -> Kind.CONTACTS
            is ConversationVolatileConfig -> Kind.CONVO_INFO_VOLATILE
            is UserGroupsConfig -> Kind.GROUPS
        }

        const val PRIORITY_HIDDEN = -1
        const val PRIORITY_VISIBLE = 0
        const val PRIORITY_PINNED = 1

    }

    external fun dirty(): Boolean
    external fun needsPush(): Boolean
    external fun needsDump(): Boolean
    external fun push(): ConfigPush
    external fun dump(): ByteArray
    external fun encryptionDomain(): String
    external fun confirmPushed(seqNo: Long, newHash: String)
    external fun merge(toMerge: Array<Pair<String,ByteArray>>): Stack<String>
    external fun currentHashes(): List<String>

    external fun configNamespace(): Int

    // Singular merge
    external fun merge(toMerge: Pair<String,ByteArray>): Stack<String>

    external fun free()

}

class Contacts(pointer: Long) : ConfigBase(pointer) {
    companion object {
        init {
            System.loadLibrary("session_util")
        }
        external fun newInstance(ed25519SecretKey: ByteArray): Contacts
        external fun newInstance(ed25519SecretKey: ByteArray, initialDump: ByteArray): Contacts
    }

    external fun get(accountId: String): Contact?
    external fun getOrConstruct(accountId: String): Contact
    external fun all(): List<Contact>
    external fun set(contact: Contact)
    external fun erase(accountId: String): Boolean

    /**
     * Similar to [updateIfExists], but will create the underlying contact if it doesn't exist before passing to [updateFunction]
     */
    fun upsertContact(accountId: String, updateFunction: Contact.()->Unit = {}) {
        when {
            accountId.startsWith(IdPrefix.BLINDED.value) -> Log.w("Loki", "Trying to create a contact with a blinded ID prefix")
            accountId.startsWith(IdPrefix.UN_BLINDED.value) -> Log.w("Loki", "Trying to create a contact with an un-blinded ID prefix")
            accountId.startsWith(IdPrefix.BLINDEDV2.value) -> Log.w("Loki", "Trying to create a contact with a blindedv2 ID prefix")
            else -> getOrConstruct(accountId).let {
                updateFunction(it)
                set(it)
            }
        }
    }

    /**
     * Updates the contact by accountId with a given [updateFunction], and applies to the underlying config.
     * the [updateFunction] doesn't run if there is no contact
     */
    private fun updateIfExists(accountId: String, updateFunction: Contact.()->Unit) {
        when {
            accountId.startsWith(IdPrefix.BLINDED.value) -> Log.w("Loki", "Trying to create a contact with a blinded ID prefix")
            accountId.startsWith(IdPrefix.UN_BLINDED.value) -> Log.w("Loki", "Trying to create a contact with an un-blinded ID prefix")
            accountId.startsWith(IdPrefix.BLINDEDV2.value) -> Log.w("Loki", "Trying to create a contact with a blindedv2 ID prefix")
            else -> get(accountId)?.let {
                updateFunction(it)
                set(it)
            }
        }
    }
}

class UserProfile(pointer: Long) : ConfigBase(pointer) {
    companion object {
        init {
            System.loadLibrary("session_util")
        }
        external fun newInstance(ed25519SecretKey: ByteArray): UserProfile
        external fun newInstance(ed25519SecretKey: ByteArray, initialDump: ByteArray): UserProfile
    }

    external fun setName(newName: String)
    external fun getName(): String?
    external fun getPic(): UserPic
    external fun setPic(userPic: UserPic)
    external fun setNtsPriority(priority: Int)
    external fun getNtsPriority(): Int
    external fun setNtsExpiry(expiryMode: ExpiryMode)
    external fun getNtsExpiry(): ExpiryMode
    external fun getCommunityMessageRequests(): Boolean
    external fun setCommunityMessageRequests(blocks: Boolean)
    external fun isBlockCommunityMessageRequestsSet(): Boolean
}

class ConversationVolatileConfig(pointer: Long): ConfigBase(pointer) {
    companion object {
        init {
            System.loadLibrary("session_util")
        }
        external fun newInstance(ed25519SecretKey: ByteArray): ConversationVolatileConfig
        external fun newInstance(ed25519SecretKey: ByteArray, initialDump: ByteArray): ConversationVolatileConfig
    }

    external fun getOneToOne(pubKeyHex: String): Conversation.OneToOne?
    external fun getOrConstructOneToOne(pubKeyHex: String): Conversation.OneToOne
    external fun eraseOneToOne(pubKeyHex: String): Boolean

    external fun getCommunity(baseUrl: String, room: String): Conversation.Community?
    external fun getOrConstructCommunity(baseUrl: String, room: String, pubKeyHex: String): Conversation.Community
    external fun getOrConstructCommunity(baseUrl: String, room: String, pubKey: ByteArray): Conversation.Community
    external fun eraseCommunity(community: Conversation.Community): Boolean
    external fun eraseCommunity(baseUrl: String, room: String): Boolean

    external fun getLegacyClosedGroup(groupId: String): Conversation.LegacyGroup?
    external fun getOrConstructLegacyGroup(groupId: String): Conversation.LegacyGroup
    external fun eraseLegacyClosedGroup(groupId: String): Boolean
    external fun erase(conversation: Conversation): Boolean

    external fun set(toStore: Conversation)

    /**
     * Erase all conversations that do not satisfy the `predicate`, similar to [MutableList.removeAll]
     */
    external fun eraseAll(predicate: (Conversation) -> Boolean): Int

    external fun sizeOneToOnes(): Int
    external fun sizeCommunities(): Int
    external fun sizeLegacyClosedGroups(): Int
    external fun size(): Int

    external fun empty(): Boolean

    external fun allOneToOnes(): List<Conversation.OneToOne>
    external fun allCommunities(): List<Conversation.Community>
    external fun allLegacyClosedGroups(): List<Conversation.LegacyGroup>
    external fun all(): List<Conversation?>

}

class UserGroupsConfig(pointer: Long): ConfigBase(pointer) {
    companion object {
        init {
            System.loadLibrary("session_util")
        }
        external fun newInstance(ed25519SecretKey: ByteArray): UserGroupsConfig
        external fun newInstance(ed25519SecretKey: ByteArray, initialDump: ByteArray): UserGroupsConfig
    }

    external fun getCommunityInfo(baseUrl: String, room: String): GroupInfo.CommunityGroupInfo?
    external fun getLegacyGroupInfo(accountId: String): GroupInfo.LegacyGroupInfo?
    external fun getOrConstructCommunityInfo(baseUrl: String, room: String, pubKeyHex: String): GroupInfo.CommunityGroupInfo
    external fun getOrConstructLegacyGroupInfo(accountId: String): GroupInfo.LegacyGroupInfo
    external fun set(groupInfo: GroupInfo)
    external fun erase(communityInfo: GroupInfo)
    external fun eraseCommunity(baseCommunityInfo: BaseCommunityInfo): Boolean
    external fun eraseCommunity(server: String, room: String): Boolean
    external fun eraseLegacyGroup(accountId: String): Boolean
    external fun sizeCommunityInfo(): Int
    external fun sizeLegacyGroupInfo(): Int
    external fun size(): Int
    external fun all(): List<GroupInfo>
    external fun allCommunityInfo(): List<GroupInfo.CommunityGroupInfo>
    external fun allLegacyGroupInfo(): List<GroupInfo.LegacyGroupInfo>
}