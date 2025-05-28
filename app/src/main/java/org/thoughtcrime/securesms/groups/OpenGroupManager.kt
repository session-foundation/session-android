package org.thoughtcrime.securesms.groups

import android.content.Context
import android.widget.Toast
import androidx.annotation.WorkerThread
import com.squareup.phrase.Phrase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import network.loki.messenger.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.pollers.OpenGroupPoller
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.StringSubstitutionConstants.COMMUNITY_NAME_KEY
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.DatabaseComponent

object OpenGroupManager {

    // flow holding information on write access for our current communities
    private val _communityWriteAccess: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap())


    fun getCommunitiesWriteAccessFlow() = _communityWriteAccess.asStateFlow()

    @WorkerThread
    suspend fun add(server: String, room: String, publicKey: String, context: Context): Pair<Long,OpenGroupApi.RoomInfo?> {
        val openGroupID = "$server.$room"
        val threadID = GroupManager.getOpenGroupThreadID(openGroupID, context)
        val storage = MessagingModuleConfiguration.shared.storage
        val threadDB = DatabaseComponent.get(context).lokiThreadDatabase()
        // Check it it's added already
        val existingOpenGroup = threadDB.getOpenGroupChat(threadID)
        if (existingOpenGroup != null) { return threadID to null }
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
            createGroupIfMissingWithPublicKey = publicKey
        )
        return threadID to info
    }

    @WorkerThread
    fun delete(server: String, room: String, context: Context) {
        try {
            val storage = MessagingModuleConfiguration.shared.storage
            val configFactory = MessagingModuleConfiguration.shared.configFactory
            val threadDB = DatabaseComponent.get(context).threadDatabase()
            val openGroupID = "${server.removeSuffix("/")}.$room"
            val threadID = GroupManager.getOpenGroupThreadID(openGroupID, context)
            val recipient = threadDB.getRecipientForThreadId(threadID) ?: return
            threadDB.setThreadArchived(threadID)
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
            val lokiThreadDB = DatabaseComponent.get(context).lokiThreadDatabase()
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

    @WorkerThread
    suspend fun addOpenGroup(urlAsString: String, context: Context): OpenGroupApi.RoomInfo? {
        val url = urlAsString.toHttpUrlOrNull() ?: return null
        val server = OpenGroup.getServer(urlAsString)
        val room = url.pathSegments.firstOrNull() ?: return null
        val publicKey = url.queryParameter("public_key") ?: return null

        return add(server.toString().removeSuffix("/"), room, publicKey, context).second // assume migrated from calling function
    }

    fun updateOpenGroup(openGroup: OpenGroup, context: Context) {
        val threadDB = DatabaseComponent.get(context).lokiThreadDatabase()
        val threadID = GroupManager.getOpenGroupThreadID(openGroup.groupId, context)
        threadDB.setOpenGroupChat(openGroup, threadID)

        // update write access for this community
        val writeAccesses = _communityWriteAccess.value.toMutableMap()
        writeAccesses[openGroup.groupId] = openGroup.canWrite
        _communityWriteAccess.value = writeAccesses
    }

    fun isUserModerator(context: Context, groupId: String, standardPublicKey: String, blindedPublicKey: String? = null): Boolean {
        val memberDatabase = DatabaseComponent.get(context).groupMemberDatabase()
        val standardRoles = memberDatabase.getGroupMemberRoles(groupId, standardPublicKey)
        val blindedRoles = blindedPublicKey?.let { memberDatabase.getGroupMemberRoles(groupId, it) } ?: emptyList()
        return standardRoles.any { it.isModerator } || blindedRoles.any { it.isModerator }
    }
}