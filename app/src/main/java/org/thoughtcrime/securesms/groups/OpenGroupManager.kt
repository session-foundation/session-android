package org.thoughtcrime.securesms.groups

import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.open_groups.api.CommunityApiExecutor
import org.session.libsession.messaging.open_groups.api.CommunityApiRequest
import org.session.libsession.messaging.open_groups.api.GetCapsApi
import org.session.libsession.messaging.open_groups.api.GetRoomDetailsApi
import org.session.libsession.messaging.open_groups.api.execute
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.withMutableUserConfigs
import org.session.libsession.utilities.withUserConfigs
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.CommunityDatabase
import org.thoughtcrime.securesms.database.LokiAPIDatabase
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "OpenGroupManager"

/**
 * Manage common operations for open groups, such as adding, deleting, and updating them.
 */
@Singleton
class OpenGroupManager @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val lokiAPIDatabase: LokiAPIDatabase,
    private val communityApiExecutor: CommunityApiExecutor,
    private val getCapsApi: Provider<GetCapsApi>,
    private val getRoomDetailsApiFactory: GetRoomDetailsApi.Factory,
    private val communityDatabase: CommunityDatabase,
    private val json: Json,
) {
    suspend fun add(server: String, room: String, publicKey: String): Unit = supervisorScope {
        // Check if the community is already added, if so, we can skip the rest of the process
        val alreadyJoined = configFactory.withUserConfigs {
            it.userGroups.getCommunityInfo(server, room)
        } != null

        if (alreadyJoined) {
            Log.d("OpenGroupManager", "Community $server is already added, skipping add process")
            return@supervisorScope
        }

        // Fetch the server's capabilities upfront to see if this server is actually running
        val getCaps = async {
            communityApiExecutor.execute(
                CommunityApiRequest(
                    serverBaseUrl = server,
                    serverPubKey = publicKey,
                    api = getCapsApi.get()
                )
            )
        }

        // Fetch room details at the same time also
        val getRoomDetails = async {
            communityApiExecutor.execute(
                CommunityApiRequest(
                    serverBaseUrl = server,
                    serverPubKey = publicKey,
                    api = getRoomDetailsApiFactory.create(room)
                )
            )
        }

        val caps = getCaps.await().capabilities
        val roomDetails = getRoomDetails.await()

        lokiAPIDatabase.setServerCapabilities(server, caps)
        communityDatabase.patchRoomInfo(Address.Community(server, room),
            json.encodeToString(OpenGroupApi.RoomInfo(roomDetails)))


        // We should be good, now go ahead and add the community to the config
        configFactory.withMutableUserConfigs { configs ->
            val community = configs.userGroups.getOrConstructCommunityInfo(
                baseUrl = server,
                room = room,
                pubKeyHex = publicKey,
            )

            configs.userGroups.set(community)
        }
    }

    fun delete(server: String, room: String) {
        configFactory.withMutableUserConfigs { configs ->
            configs.userGroups.eraseCommunity(server, room)
            configs.convoInfoVolatile.eraseCommunity(server, room)
        }
    }
}