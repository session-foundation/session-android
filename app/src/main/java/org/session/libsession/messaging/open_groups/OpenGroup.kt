package org.session.libsession.messaging.open_groups

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import network.loki.messenger.libsession_util.util.BaseCommunityInfo
import org.session.libsession.utilities.Address

@Deprecated("This class is no longer used except in migration. Use RoomInfo instead")
@Serializable
data class OpenGroup(
    val server: String,
    val room: String,
    @SerialName("displayName") // This rename caters for existing data
    val name: String,
    val description: String? = null,
    val publicKey: String,
    val imageId: String?,
    val infoUpdates: Int,
    val canWrite: Boolean,
    val isAdmin: Boolean = false, // The default value caters for existing data
    val isModerator: Boolean = false, // The default value caters for existing data
) {
    val id: String get() = groupId

    companion object {
        /**
         * Returns the group ID for this community info. The group ID is the session android unique
         * way of identifying a community. It itself isn't super useful but it's used to construct
         * the [Address] for communities.
         *
         */
        val BaseCommunityInfo.groupId: String
            get() = "${baseUrl}.${room}"

    }

    val groupId: String get() = "$server.$room"
}