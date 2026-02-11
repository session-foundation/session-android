package org.thoughtcrime.securesms.groups

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import org.session.libsession.messaging.groups.GroupScope
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.sending_receiving.notifications.NotificationServer
import org.session.libsession.messaging.utilities.UpdateMessageData
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.waitUntilGroupConfigsPushed
import org.session.libsession.utilities.withGroupConfigs
import org.session.libsession.utilities.withMutableGroupConfigs
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.session.protos.SessionProtos
import org.session.protos.SessionProtos.GroupUpdateMessage
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.api.server.ServerApiRequest
import org.thoughtcrime.securesms.api.server.execute
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.notifications.PushUnregisterApi

@HiltWorker
class GroupLeavingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val storage: Storage,
    private val configFactory: ConfigFactory,
    private val groupScope: GroupScope,
    private val tokenFetcher: TokenFetcher,
    private val serverApiExecutor: ServerApiExecutor,
    private val pushUnregisterApiFactory: PushUnregisterApi.Factory,
    private val messageSender: MessageSender,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val groupId = requireNotNull(inputData.getString(KEY_GROUP_ID)) {
            "Group ID must be provided"
        }.let(::AccountId)

        // delete this group instead of leaving.
        val deleteGroup = inputData.getBoolean(KEY_DELETE_GROUP, false)

        Log.d(TAG, "Group leaving work started for $groupId")

        return groupScope.launchAndWait(groupId, "GroupLeavingWorker") {
            val group = configFactory.getGroup(groupId)

            // Make sure we only have one group leaving control message
            storage.deleteGroupInfoMessages(groupId, UpdateMessageData.Kind.GroupLeaving::class.java)
            storage.insertGroupInfoLeaving(groupId)

            // Best effort to unsubscribe ourselves from the registration server.
            // Note that this process can only be done on the device that is leaving the group,
            // on a linked device, we might not have the credentials to do so.
            val currentToken = tokenFetcher.token.value
            if (currentToken != null) {
                try {
                    val groupAuth = configFactory.getGroupAuth(groupId)

                    if (groupAuth != null) {
                        serverApiExecutor.execute(
                            ServerApiRequest(
                                serverBaseUrl = NotificationServer.LATEST.url,
                                serverX25519PubKeyHex = NotificationServer.LATEST.publicKey,
                                api = pushUnregisterApiFactory.create(
                                    token = currentToken,
                                    swarmAuth = groupAuth,
                                )
                            )
                        )

                        Log.d(TAG, "Unsubscribed from group $groupId successfully")
                    }

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unsubscribe from group $groupId", e)
                }
            }

            try {
                if (group?.destroyed != true) {
                    // Only send the left/left notification group message when we are not kicked and we are not the only admin (only admin has a special treatment)
                    val weAreTheOnlyAdmin = configFactory.withGroupConfigs(groupId) { config ->
                        val allMembers = config.groupMembers.all()
                        allMembers.count { it.admin } == 1 &&
                                allMembers.first { it.admin }
                                    .accountId() == storage.getUserPublicKey()
                    }

                    if (group != null && !group.kicked && !weAreTheOnlyAdmin) {
                        val address = Address.fromSerialized(groupId.hexString)
                        val statusChannel = Channel<kotlin.Result<Unit>>()

                        // Always send a "XXX left" message to the group if we can
                        messageSender.send(
                            GroupUpdated(
                                GroupUpdateMessage.newBuilder()
                                    .setMemberLeftNotificationMessage(SessionProtos.GroupUpdateMemberLeftNotificationMessage.getDefaultInstance())
                                    .build()
                            ),
                            address,
                            statusCallback = statusChannel,
                        )

                        // If we are not the only admin, send a left message for other admin to handle the member removal
                        // We'll have to wait for this message to be sent before going ahead to delete the group
                        messageSender.send(
                            GroupUpdated(
                                GroupUpdateMessage.newBuilder()
                                    .setMemberLeftMessage(SessionProtos.GroupUpdateMemberLeftMessage.getDefaultInstance())
                                    .build()
                            ),
                            address,
                            statusCallback = statusChannel
                        )

                        // Wait for both messages to be sent
                        repeat(2) {
                            statusChannel.receive().getOrThrow()
                        }
                    }

                    // We now have an admin option to leave group so we need a way of Deleting the group
                    // even if there are more admins
                    if ((weAreTheOnlyAdmin || deleteGroup)) {
                        try {
                            configFactory.withMutableGroupConfigs(groupId) { configs ->
                                configs.groupInfo.destroyGroup()
                            }

                            // Must wait until the config is pushed, otherwise if we go through the rest
                            // of the code it will destroy the conversation, destroying the necessary configs
                            // along the way, we won't be able to push the "destroyed" state anymore.
                            configFactory.waitUntilGroupConfigsPushed(groupId, timeoutMills = 0L)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // If the destruction of group can't be done, there's nothing
                            // else we can do. So we will proceed with the rest where
                            // we remove the group entry from the database.
                            Log.e(TAG, "Error while destroying group $groupId. Proceeding...", e)
                        }
                    }
                }

                // Delete conversation and group configs
                configFactory.removeGroup(groupId)
                Log.d(TAG, "Group $groupId left successfully")
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                storage.insertGroupInfoErrorQuit(groupId)
                Log.e(TAG, "Failed to leave group $groupId", e)
                if (e is NonRetryableException) {
                    Result.failure()
                } else {
                    Result.retry()
                }
            } finally {
                storage.deleteGroupInfoMessages(groupId, UpdateMessageData.Kind.GroupLeaving::class.java)
            }
        }
    }

    companion object {
        private const val TAG = "GroupLeavingWorker"

        private const val KEY_GROUP_ID = "group_id"
        private const val KEY_DELETE_GROUP = "delete_group"

        fun schedule(context: Context, groupId: AccountId, deleteGroup : Boolean = false) {
            WorkManager.getInstance(context)
                .enqueue(
                    OneTimeWorkRequestBuilder<GroupLeavingWorker>()
                        .addTag(KEY_GROUP_ID)
                        .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                        .setInputData(
                            Data.Builder()
                                .putString(KEY_GROUP_ID, groupId.hexString)
                                .putBoolean(KEY_DELETE_GROUP, deleteGroup)
                                .build()
                        )
                        .build()
                )
        }
    }
}
