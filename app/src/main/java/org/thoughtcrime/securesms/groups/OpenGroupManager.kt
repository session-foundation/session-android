package org.thoughtcrime.securesms.groups

import android.content.Context
import android.widget.Toast
import com.squareup.phrase.Phrase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import network.loki.messenger.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.pollers.OpenGroupPoller
import org.session.libsession.messaging.sending_receiving.pollers.OpenGroupPollerManager
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.StringSubstitutionConstants.COMMUNITY_NAME_KEY
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.GroupMemberDatabase
import org.thoughtcrime.securesms.database.LokiThreadDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manage common operations for open groups, such as adding, deleting, and updating them.
 */
@Singleton
class OpenGroupManager @Inject constructor(
    private val storage: StorageProtocol,
    private val lokiThreadDB: LokiThreadDatabase,
    private val threadDb: ThreadDatabase,
    private val configFactory: ConfigFactoryProtocol,
    private val groupMemberDatabase: GroupMemberDatabase,
    private val pollerManager: OpenGroupPollerManager,
) {

    // flow holding information on write access for our current communities
    private val _communityWriteAccess: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap())


    fun getCommunitiesWriteAccessFlow() = _communityWriteAccess.asStateFlow()

    suspend fun add(server: String, room: String, publicKey: String, context: Context) {
        val openGroupID = "$server.$room"
        val threadID = GroupManager.getOpenGroupThreadID(openGroupID, context)
        // Check it it's added already
        val existingOpenGroup = lokiThreadDB.getOpenGroupChat(threadID)
        if (existingOpenGroup != null) {
            return
        }
        // Clear any existing data if needed
        storage.removeLastDeletionServerID(room, server)
        storage.removeLastMessageServerID(room, server)
        storage.removeLastInboxMessageId(server)
        storage.removeLastOutboxMessageId(server)
        // Store the public key
        storage.setOpenGroupPublicKey(server, publicKey)
        // Get capabilities & room info
        val (capabilities, info) = OpenGroupApi.getCapabilitiesAndRoomInfo(room, server).await()
        storage.setServerCapabilities(server, capabilities.capabilities)
        // Create the group locally if not available already
        if (threadID < 0) {
            GroupManager.createOpenGroup(openGroupID, context, null, info.name)
        }

        OpenGroupPoller.handleRoomPollInfo(
            storage = storage,
            server = server,
            roomToken = room,
            pollInfo = info.toPollInfo(),
            createGroupIfMissingWithPublicKey = publicKey,
            memberDb = groupMemberDatabase,
        )

        // If existing poller for the same server exist, we'll request a poll once now so new room
        // can be polled immediately.
        pollerManager.pollers.value[server]?.poller?.requestPollOnce()
    }

    fun delete(server: String, room: String, context: Context) {
        try {
            val openGroupID = "${server.removeSuffix("/")}.$room"
            val threadID = GroupManager.getOpenGroupThreadID(openGroupID, context)
            val recipient = threadDb.getRecipientForThreadId(threadID) ?: return
            val groupID = recipient.address.toString()
            // Stop the poller if needed
            configFactory.withMutableUserConfigs {
                it.userGroups.eraseCommunity(server, room)
                it.convoInfoVolatile.eraseCommunity(server, room)
            }
            // Delete
            storage.removeLastDeletionServerID(room, server)
            storage.removeLastMessageServerID(room, server)
            storage.removeLastInboxMessageId(server)
            storage.removeLastOutboxMessageId(server)
            lokiThreadDB.removeOpenGroupChat(threadID)
            storage.deleteConversation(threadID)       // Must be invoked on a background thread
            GroupManager.deleteGroup(groupID, context) // Must be invoked on a background thread
        }
        catch (e: Exception) {
            Log.e("Loki", "Failed to leave (delete) community", e)
            val serverAndRoom = "$server.$room"
            val txt = Phrase.from(context, R.string.communityLeaveError).put(COMMUNITY_NAME_KEY, serverAndRoom).format().toString()
            Toast.makeText(context, txt, Toast.LENGTH_LONG).show()
        }
    }

    suspend fun addOpenGroup(urlAsString: String, context: Context) {
        val url = urlAsString.toHttpUrlOrNull() ?: return
        val server = OpenGroup.getServer(urlAsString)
        val room = url.pathSegments.firstOrNull() ?: return
        val publicKey = url.queryParameter("public_key") ?: return

        add(server.toString().removeSuffix("/"), room, publicKey, context) // assume migrated from calling function
    }

    fun updateOpenGroup(openGroup: OpenGroup, context: Context) {
        val threadID = GroupManager.getOpenGroupThreadID(openGroup.groupId, context)
        lokiThreadDB.setOpenGroupChat(openGroup, threadID)

        // update write access for this community
        val writeAccesses = _communityWriteAccess.value.toMutableMap()
        writeAccesses[openGroup.groupId] = openGroup.canWrite
        _communityWriteAccess.value = writeAccesses
    }

    fun isUserModerator(
        groupId: String,
        standardPublicKey: String,
        blindedPublicKey: String? = null
    ): Boolean {
        val standardRole = groupMemberDatabase.getGroupMemberRole(groupId, standardPublicKey)
        val blindedRole = blindedPublicKey?.let { groupMemberDatabase.getGroupMemberRole(groupId, it) }
        return standardRole?.isModerator == true || blindedRole?.isModerator == true
    }
}