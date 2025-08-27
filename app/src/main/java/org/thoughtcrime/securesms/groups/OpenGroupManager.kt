package org.thoughtcrime.securesms.groups

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.pollers.OpenGroupPollerManager
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.LokiAPIDatabase
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OpenGroupManager"

/**
 * Manage common operations for open groups, such as adding, deleting, and updating them.
 */
@Singleton
class OpenGroupManager @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val pollerManager: OpenGroupPollerManager,
    private val lokiAPIDatabase: LokiAPIDatabase,
) {
    suspend fun add(server: String, room: String, publicKey: String) {
        // Fetch the server's capabilities upfront to see if this server is actually running
        // Note: this process is not essential to adding a community, just a nice to have test
        // for the user to see if the server they are adding is reachable.
        // The addition of the community to the config later will always succeed and the poller
        // will be started regardless of the server's status.
        val caps = OpenGroupApi.getCapabilities(server, serverPubKeyHex = publicKey).await()
        lokiAPIDatabase.setServerCapabilities(server, caps.capabilities)

        // We should be good, now go ahead and add the community to the config
        configFactory.withMutableUserConfigs { configs ->
            val community = configs.userGroups.getOrConstructCommunityInfo(
                baseUrl = server,
                room = room,
                pubKeyHex = publicKey,
            )

            configs.userGroups.set(community)
        }

        Log.d(TAG, "Waiting for poller for server $server to be started.")

        // Wait until we have a poller for the server, and then request one poll
        pollerManager.pollers
            .mapNotNull { it[server] }
            .first()
            .poller
            .requestPollAndAwait()
    }

    fun delete(server: String, room: String) {
        configFactory.withMutableUserConfigs { configs ->
            configs.userGroups.eraseCommunity(server, room)
            configs.convoInfoVolatile.eraseCommunity(server, room)
        }
    }

    suspend fun addOpenGroup(urlAsString: String) {
        val url = urlAsString.toHttpUrlOrNull() ?: return
        val server = OpenGroup.getServer(urlAsString)
        val room = url.pathSegments.firstOrNull() ?: return
        val publicKey = url.queryParameter("public_key") ?: return

        add(server.toString().removeSuffix("/"), room, publicKey) // assume migrated from calling function
    }
}