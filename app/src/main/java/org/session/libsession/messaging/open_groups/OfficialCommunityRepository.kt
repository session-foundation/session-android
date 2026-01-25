package org.session.libsession.messaging.open_groups

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.open_groups.api.CommunityApiExecutor
import org.session.libsession.messaging.open_groups.api.CommunityApiRequest
import org.session.libsession.messaging.open_groups.api.DownloadFileApi
import org.session.libsession.messaging.open_groups.api.GetCapsApi
import org.session.libsession.messaging.open_groups.api.GetRoomsApi
import org.session.libsession.messaging.open_groups.api.execute
import org.thoughtcrime.securesms.dependencies.ManagerScope
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class OfficialCommunityRepository @Inject constructor(
    storage: StorageProtocol,
    communityApiExecutor: CommunityApiExecutor,
    getRoomsApi: Provider<GetRoomsApi>,
    getCapsApi: Provider<GetCapsApi>,
    downloadFileApiFactory: DownloadFileApi.Factory,
    @ManagerScope scope: CoroutineScope,
) {
    private val refreshTrigger = MutableSharedFlow<Unit>()

    @Suppress("OPT_IN_USAGE")
    private val officialCommunitiesCache =
        refreshTrigger
            .flatMapLatest {
                flow {
                    emit(runCatching {
                        coroutineScope {
                            val roomsDeferred = async {
                                communityApiExecutor.execute(
                                    CommunityApiRequest(
                                        serverBaseUrl = OFFICIAL_COMMUNITY_URL,
                                        serverPubKey = OFFICIAL_COMMUNITY_X25519_PUB_KEY_HEX,
                                        api = getRoomsApi.get()
                                    )
                                )
                            }

                            val capsDeferred = async {
                                communityApiExecutor.execute(
                                    CommunityApiRequest(
                                        serverBaseUrl = OFFICIAL_COMMUNITY_URL,
                                        serverPubKey = OFFICIAL_COMMUNITY_X25519_PUB_KEY_HEX,
                                        api = getCapsApi.get(),
                                    )
                                )
                            }


                            val rooms = roomsDeferred.await()
                            val roomAvatars = rooms.associate { room ->
                                room.token to room.imageId?.let { fileId ->
                                    try {
                                         communityApiExecutor.execute(
                                            CommunityApiRequest(
                                                serverBaseUrl = OFFICIAL_COMMUNITY_URL,
                                                serverPubKey = OFFICIAL_COMMUNITY_X25519_PUB_KEY_HEX,
                                                api = downloadFileApiFactory.create(
                                                    room = room.token,
                                                    fileId = fileId,
                                                )
                                            )
                                        ).toByteArraySlice()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to download official community room avatar for room ${room.token}", e)
                                        null
                                    }
                                }
                            }

                            storage.setServerCapabilities(OFFICIAL_COMMUNITY_URL, capsDeferred.await().capabilities)

                            rooms.map { room ->
                                OpenGroupApi.DefaultGroup(
                                    serverUrl = OFFICIAL_COMMUNITY_URL,
                                    id = room.token,
                                    name = room.name,
                                    image = roomAvatars[room.token],
                                    publicKey = OFFICIAL_COMMUNITY_X25519_PUB_KEY_HEX
                                )
                            }
                        }
                    })
                }
            }
            .shareIn(scope, SharingStarted.Lazily)

    suspend fun fetchOfficialCommunities(): List<OpenGroupApi.DefaultGroup> {
        if (officialCommunitiesCache.replayCache.firstOrNull()?.isFailure == true) {
            refreshTrigger.emit(Unit)
        }

        return officialCommunitiesCache.first().getOrThrow()
    }

    companion object {
        private const val OFFICIAL_COMMUNITY_URL = "https://official.session.org"
        private const val OFFICIAL_COMMUNITY_X25519_PUB_KEY_HEX = "a03c383cf63c3c4efe67acc52112a6dd734b3a946b9545f488aaa93da7991238"

        private const val TAG = "OfficialCommunityRepo"
    }
}