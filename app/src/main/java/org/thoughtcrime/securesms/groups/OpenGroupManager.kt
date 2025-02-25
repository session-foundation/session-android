package org.thoughtcrime.securesms.groups

import android.content.Context
import android.widget.Toast
import androidx.annotation.WorkerThread
import com.squareup.phrase.Phrase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import network.loki.messenger.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.pollers.OpenGroupPoller
import org.session.libsession.utilities.StringSubstitutionConstants.COMMUNITY_NAME_KEY
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import org.thoughtcrime.securesms.util.ConfigurationMessageUtilities

object OpenGroupManager {
    private val executorService = Executors.newScheduledThreadPool(4)
    private val pollers = mutableMapOf<String, OpenGroupPoller>() // One for each server
    private var isPolling = false
    private val pollUpdaterLock = Any()

    val isAllCaughtUp: Boolean
        get() {
            pollers.values.forEach { poller ->
                val jobID = poller.secondToLastJob?.id
                jobID?.let {
                    val storage = MessagingModuleConfiguration.shared.storage
                    if (storage.getMessageReceiveJob(jobID) == null) {
                        // If the second to last job is done, it means we are now handling the last job
                        poller.isCaughtUp = true
                        poller.secondToLastJob = null
                    }
                }
                if (!poller.isCaughtUp) { return false }
            }
            return true
        }

    // flow holding information on write access for our current communities
    private val _communityWriteAccess: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap())

    fun startPolling() {
        if (isPolling) { return }
        isPolling = true
        val storage = MessagingModuleConfiguration.shared.storage
        val (serverGroups, toDelete) = storage.getAllOpenGroups().values.partition { storage.getThreadId(it) != null }
        toDelete.forEach { openGroup ->
            Log.w("Loki", "Need to delete a group")
            delete(openGroup.server, openGroup.room, MessagingModuleConfiguration.shared.context)
        }

        val servers = serverGroups.map { it.server }.toSet()
        synchronized(pollUpdaterLock) {
            servers.forEach { server ->
                pollers[server]?.stop() // Shouldn't be necessary
                pollers[server] = OpenGroupPoller(server, executorService).apply { startIfNeeded() }
            }
        }
    }

    fun stopPolling() {
        synchronized(pollUpdaterLock) {
            pollers.forEach { it.value.stop() }
            pollers.clear()
            isPolling = false
        }
    }

    fun getCommunitiesWriteAccessFlow() = _communityWriteAccess.asStateFlow()

    @WorkerThread
    fun add(server: String, room: String, publicKey: String, context: Context): Pair<Long,OpenGroupApi.RoomInfo?> {
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
        val (capabilities, info) = OpenGroupApi.getCapabilitiesAndRoomInfo(room, server).get()
        storage.setServerCapabilities(server, capabilities.capabilities)
        // Create the group locally if not available already
        if (threadID < 0) {
            GroupManager.createOpenGroup(openGroupID, context, null, info.name)
        }
        OpenGroupPoller.handleRoomPollInfo(
            server = server,
            roomToken = room,
            pollInfo = info.toPollInfo(),
            createGroupIfMissingWithPublicKey = publicKey
        )
        return threadID to info
    }

    fun restartPollerForServer(server: String) {
        // Start the poller if needed
        synchronized(pollUpdaterLock) {
            pollers[server]?.stop()
            pollers[server]?.startIfNeeded() ?: run {
                val poller = OpenGroupPoller(server, executorService)
                Log.d("Loki", "Starting poller for open group: $server")
                pollers[server] = poller
                poller.startIfNeeded()
            }
        }
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
            val groupID = recipient.address.serialize()
            // Stop the poller if needed
            val openGroups = storage.getAllOpenGroups().filter { it.value.server == server }
            if (openGroups.isNotEmpty()) {
                synchronized(pollUpdaterLock) {
                    val poller = pollers[server]
                    poller?.stop()
                    pollers.remove(server)
                }
            }
            configFactory.userGroups?.eraseCommunity(server, room)
            configFactory.convoVolatile?.eraseCommunity(server, room)
            // Delete
            storage.removeLastDeletionServerID(room, server)
            storage.removeLastMessageServerID(room, server)
            storage.removeLastInboxMessageId(server)
            storage.removeLastOutboxMessageId(server)
            val lokiThreadDB = DatabaseComponent.get(context).lokiThreadDatabase()
            lokiThreadDB.removeOpenGroupChat(threadID)
            storage.deleteConversation(threadID)       // Must be invoked on a background thread
            GroupManager.deleteGroup(groupID, context) // Must be invoked on a background thread
            ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(context)
        }
        catch (e: Exception) {
            Log.e("Loki", "Failed to leave (delete) community", e)
            val serverAndRoom = "$server.$room"
            val txt = Phrase.from(context, R.string.communityLeaveError).put(COMMUNITY_NAME_KEY, serverAndRoom).format().toString()
            Toast.makeText(context, txt, Toast.LENGTH_LONG).show()
        }
    }

    @WorkerThread
    fun addOpenGroup(urlAsString: String, context: Context): OpenGroupApi.RoomInfo? {
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