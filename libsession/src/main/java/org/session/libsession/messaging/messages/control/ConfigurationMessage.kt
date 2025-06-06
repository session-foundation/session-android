package org.session.libsession.messaging.messages.control

import com.google.protobuf.ByteString
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.copyExpiration
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.ProfileKeyUtil
import org.session.libsignal.crypto.ecc.DjbECPrivateKey
import org.session.libsignal.crypto.ecc.DjbECPublicKey
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.removingIdPrefixIfNeeded
import org.session.libsignal.utilities.toHexString

class ConfigurationMessage(var closedGroups: List<ClosedGroup>, var openGroups: List<String>, var contacts: List<Contact>,
    var displayName: String, var profilePicture: String?, var profileKey: ByteArray) : ControlMessage() {

    override val isSelfSendValid: Boolean = true

    override fun shouldDiscardIfBlocked(): Boolean = true

    class ClosedGroup(var publicKey: String, var name: String, var encryptionKeyPair: ECKeyPair?, var members: List<String>, var admins: List<String>) {
        val isValid: Boolean get() = members.isNotEmpty() && admins.isNotEmpty()

        internal constructor() : this("", "", null, listOf(), listOf())

        override fun toString(): String {
            return name
        }

        companion object {

            fun fromProto(proto: SignalServiceProtos.ConfigurationMessage.ClosedGroup): ClosedGroup? {
                if (!proto.hasPublicKey() || !proto.hasName() || !proto.hasEncryptionKeyPair()) return null
                val publicKey = proto.publicKey.toByteArray().toHexString()
                val name = proto.name
                val encryptionKeyPairAsProto = proto.encryptionKeyPair
                val encryptionKeyPair = ECKeyPair(DjbECPublicKey(encryptionKeyPairAsProto.publicKey.toByteArray().removingIdPrefixIfNeeded()),
                    DjbECPrivateKey(encryptionKeyPairAsProto.privateKey.toByteArray()))
                val members = proto.membersList.map { it.toByteArray().toHexString() }
                val admins = proto.adminsList.map { it.toByteArray().toHexString() }
                return ClosedGroup(publicKey, name, encryptionKeyPair, members, admins)
            }
        }

        fun toProto(): SignalServiceProtos.ConfigurationMessage.ClosedGroup? {
            val result = SignalServiceProtos.ConfigurationMessage.ClosedGroup.newBuilder()
            result.publicKey = ByteString.copyFrom(Hex.fromStringCondensed(publicKey))
            result.name = name
            val encryptionKeyPairAsProto = SignalServiceProtos.KeyPair.newBuilder()
            encryptionKeyPairAsProto.publicKey = ByteString.copyFrom(encryptionKeyPair!!.publicKey.serialize().removingIdPrefixIfNeeded())
            encryptionKeyPairAsProto.privateKey = ByteString.copyFrom(encryptionKeyPair!!.privateKey.serialize())
            result.encryptionKeyPair = encryptionKeyPairAsProto.build()
            result.addAllMembers(members.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) })
            result.addAllAdmins(admins.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) })
            return result.build()
        }
    }

    class Contact(var publicKey: String, var name: String, var profilePicture: String?, var profileKey: ByteArray?, var isApproved: Boolean?, var isBlocked: Boolean?, var didApproveMe: Boolean?) {

        internal constructor() : this("", "", null, null, null, null, null)

        companion object {

            fun fromProto(proto: SignalServiceProtos.ConfigurationMessage.Contact): Contact? {
                if (!proto.hasName()) return null
                val publicKey = proto.publicKey.toByteArray().toHexString()
                val name = proto.name
                val profilePicture = if (proto.hasProfilePicture()) proto.profilePicture else null
                val profileKey = if (proto.hasProfileKey()) proto.profileKey.toByteArray() else null
                val isApproved = if (proto.hasIsApproved()) proto.isApproved else null
                val isBlocked = if (proto.hasIsBlocked()) proto.isBlocked else null
                val didApproveMe = if (proto.hasDidApproveMe()) proto.didApproveMe else null
                return Contact(publicKey, name, profilePicture, profileKey, isApproved, isBlocked, didApproveMe)
            }
        }

        fun toProto(): SignalServiceProtos.ConfigurationMessage.Contact? {
            val result = SignalServiceProtos.ConfigurationMessage.Contact.newBuilder()
            result.name = this.name
            try {
                result.publicKey = ByteString.copyFrom(Hex.fromStringCondensed(publicKey))
            } catch (e: Exception) {
                return null
            }
            val profilePicture = profilePicture
            if (!profilePicture.isNullOrEmpty()) {
                result.profilePicture = profilePicture
            }
            val profileKey = profileKey
            if (profileKey != null) {
                result.profileKey = ByteString.copyFrom(profileKey)
            }
            val isApproved = isApproved
            if (isApproved != null) {
                result.isApproved = isApproved
            }
            val isBlocked = isBlocked
            if (isBlocked != null) {
                result.isBlocked = isBlocked
            }
            val didApproveMe = didApproveMe
            if (didApproveMe != null) {
                result.didApproveMe = didApproveMe
            }
            return result.build()
        }
    }

    companion object {

        fun getCurrent(displayName: String, profilePicture: String?, contacts: List<Contact>): ConfigurationMessage? {
            val closedGroups = mutableListOf<ClosedGroup>()
            val openGroups = mutableListOf<String>()
            val sharedConfig = MessagingModuleConfiguration.shared
            val storage = sharedConfig.storage
            val context = sharedConfig.context
            val profileKey = ProfileKeyUtil.getProfileKey(context)
            val groups = storage.getAllGroups(includeInactive = false)
            for (group in groups) {
                if (group.isLegacyGroup && group.isActive) {
                    if (!group.members.contains(Address.fromSerialized(storage.getUserPublicKey()!!))) continue
                    val groupPublicKey = GroupUtil.doubleDecodeGroupID(group.encodedId).toHexString()
                    val encryptionKeyPair = storage.getLatestClosedGroupEncryptionKeyPair(groupPublicKey) ?: continue
                    val closedGroup = ClosedGroup(
                        groupPublicKey,
                        group.title,
                        encryptionKeyPair,
                        group.members.map { it.toString() },
                        group.admins.map { it.toString() }
                    )
                    closedGroups.add(closedGroup)
                }
                if (group.isCommunity) {
                    val threadID = storage.getThreadId(group.encodedId) ?: continue
                    val openGroup = storage.getOpenGroup(threadID)
                    val shareUrl = openGroup?.joinURL ?: continue
                    openGroups.add(shareUrl)
                }
            }
            return ConfigurationMessage(closedGroups, openGroups, contacts, displayName, profilePicture, profileKey)
        }

        fun fromProto(proto: SignalServiceProtos.Content): ConfigurationMessage? {
            if (!proto.hasConfigurationMessage()) return null
            val configurationProto = proto.configurationMessage
            val closedGroups = configurationProto.closedGroupsList.mapNotNull { ClosedGroup.fromProto(it) }
            val openGroups = configurationProto.openGroupsList
            val displayName = configurationProto.displayName
            val profilePicture = configurationProto.profilePicture
            val profileKey = configurationProto.profileKey
            val contacts = configurationProto.contactsList.mapNotNull { Contact.fromProto(it) }
            return ConfigurationMessage(closedGroups, openGroups, contacts, displayName, profilePicture, profileKey.toByteArray())
                    .copyExpiration(proto)
        }
    }

    internal constructor(): this(listOf(), listOf(), listOf(), "", null, byteArrayOf())

    override fun toProto(): SignalServiceProtos.Content? {
        val configurationProto = SignalServiceProtos.ConfigurationMessage.newBuilder()
        configurationProto.addAllClosedGroups(closedGroups.mapNotNull { it.toProto() })
        configurationProto.addAllOpenGroups(openGroups)
        configurationProto.addAllContacts(this.contacts.mapNotNull { it.toProto() })
        configurationProto.displayName = displayName
        val profilePicture = profilePicture
        if (!profilePicture.isNullOrEmpty()) {
            configurationProto.profilePicture = profilePicture
        }
        configurationProto.profileKey = ByteString.copyFrom(profileKey)
        val contentProto = SignalServiceProtos.Content.newBuilder()
        contentProto.configurationMessage = configurationProto.build()
        return contentProto.build()
    }

    override fun toString(): String {
        return """ 
            ConfigurationMessage(
                closedGroups: ${(closedGroups)},
                openGroups: ${(openGroups)},
                displayName: $displayName,
                profilePicture: $profilePicture,
                profileKey: $profileKey
            )
        """.trimIndent()
    }
}