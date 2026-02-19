package org.session.libsession.messaging.utilities

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsignal.utilities.Log
import org.session.protos.SessionProtos.GroupUpdateInfoChangeMessage
import org.session.protos.SessionProtos.GroupUpdateMemberChangeMessage.Type

/**
 * Represents certain type of message.
 *
 * This class is an afterthought to save "rich message" into a message's body as JSON text.
 * We've since moved away from this setup, a dedicated
 * [org.thoughtcrime.securesms.database.model.content.MessageContent] is now used for rich
 * message types.
 *
 * If you want to store a new message type, you should use the new setup instead.
 *
 * We'll look into migrating this class into the new setup in the future.
 */
@Serializable
class UpdateMessageData(val kind: Kind) {

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonClassDiscriminator("@type")
    sealed interface Kind {
        @Serializable
        @SerialName("GroupCreation")
        data object GroupCreation: Kind

        @Serializable
        @SerialName("GroupNameChange")
        class GroupNameChange(val name: String): Kind

        @Serializable
        @SerialName("GroupMemberAdded")
        class GroupMemberAdded(val updatedMembers: Collection<String>, val groupName: String): Kind

        @Serializable
        @SerialName("GroupMemberRemoved")
        class GroupMemberRemoved(val updatedMembers: Collection<String>, val groupName:String): Kind

        @Serializable
        @SerialName("GroupMemberLeft")
        class GroupMemberLeft(val updatedMembers: Collection<String>, val groupName:String): Kind

        @Serializable
        @SerialName("GroupMemberUpdated")
        class GroupMemberUpdated(
            val sessionIds: List<String>,
            val type: MemberUpdateType?,
            val groupName: String,
            val historyShared: Boolean
        ): Kind

        @Serializable
        @SerialName("GroupAvatarUpdated")
        data object GroupAvatarUpdated: Kind

        @Serializable
        @SerialName("GroupExpirationUpdated")
        class GroupExpirationUpdated(val updatedExpiration: Long, val updatingAdmin: String): Kind

        @Serializable
        @SerialName("OpenGroupInvitation")
        class OpenGroupInvitation(val groupUrl: String, val groupName: String): Kind

        @Serializable
        @SerialName("GroupLeaving")
        data object GroupLeaving: Kind

        @Serializable
        @SerialName("GroupErrorQuit")
        data class GroupErrorQuit(val groupName: String): Kind

        @Serializable
        @SerialName("GroupInvitation")
        class GroupInvitation(
            val groupAccountId: String,
            val invitingAdminId: String,
            val invitingAdminName: String?,
            val groupName: String
        ) : Kind
    }

    @Serializable
    @JsonClassDiscriminator("@type")
    sealed interface MemberUpdateType {
        @Serializable
        @SerialName("ADDED")
        data object ADDED: MemberUpdateType

        @Serializable
        @SerialName("REMOVED")
        data object REMOVED: MemberUpdateType

        @Serializable
        @SerialName("PROMOTED")
        data object PROMOTED: MemberUpdateType

    }


    companion object {
        val TAG = UpdateMessageData::class.simpleName

        fun buildGroupUpdate(groupUpdated: GroupUpdated, groupName: String): UpdateMessageData? {
            val inner = groupUpdated.inner
            return when {
                inner.hasMemberChangeMessage() -> {
                    val memberChange = inner.memberChangeMessage
                    val type = when (memberChange.type) {
                        Type.ADDED -> MemberUpdateType.ADDED
                        Type.PROMOTED -> MemberUpdateType.PROMOTED
                        Type.REMOVED -> MemberUpdateType.REMOVED
                        null -> null
                    }
                    val members = memberChange.memberSessionIdsList
                    UpdateMessageData(Kind.GroupMemberUpdated(members, type, groupName, memberChange.historyShared))
                }
                inner.hasInfoChangeMessage() -> {
                    val infoChange = inner.infoChangeMessage
                    val type = infoChange.type
                    when (type) {
                        GroupUpdateInfoChangeMessage.Type.NAME -> Kind.GroupNameChange(infoChange.updatedName)
                        GroupUpdateInfoChangeMessage.Type.AVATAR -> Kind.GroupAvatarUpdated
                        GroupUpdateInfoChangeMessage.Type.DISAPPEARING_MESSAGES -> Kind.GroupExpirationUpdated(
                            updatedExpiration = infoChange.updatedExpiration.toLong(),
                            updatingAdmin = groupUpdated.sender.orEmpty()
                        )
                        else -> null
                    }?.let { UpdateMessageData(it) }
                }
                inner.hasMemberLeftNotificationMessage() -> UpdateMessageData(Kind.GroupMemberLeft(
                    updatedMembers = listOf(groupUpdated.sender.orEmpty()),
                    groupName = groupName
                ))
                else -> null
            }
        }

        fun buildOpenGroupInvitation(url: String, name: String): UpdateMessageData {
            return UpdateMessageData(Kind.OpenGroupInvitation(url, name))
        }

        fun buildGroupLeaveUpdate(newType: Kind): UpdateMessageData {
            return UpdateMessageData(newType)
        }

        @JvmStatic
        fun fromJSON(json: Json, value: String): UpdateMessageData? {
             return runCatching {
                 json.decodeFromString<UpdateMessageData>(value)
             }.onFailure { Log.e(TAG, "Error decoding updateMessageData", it) }
                 .getOrNull()
        }

    }

    fun toJSON(json: Json): String {
        return json.encodeToString(this)
    }

    fun isGroupLeavingKind(): Boolean {
        return kind is Kind.GroupLeaving
    }

    fun isGroupErrorQuitKind(): Boolean {
        return kind is Kind.GroupErrorQuit
    }
}
