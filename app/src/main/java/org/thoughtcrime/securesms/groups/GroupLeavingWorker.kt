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
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.UpdateMessageData
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.waitUntilGroupConfigsPushed
import org.session.libsignal.exceptions.NonRetryableException
import org.session.libsignal.protos.SignalServiceProtos.DataMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateMessage
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.dependencies.ConfigFactory

@HiltWorker
class GroupLeavingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val storage: Storage,
    private val configFactory: ConfigFactory,
    private val groupScope: GroupScope,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val groupId = requireNotNull(inputData.getString(KEY_GROUP_ID)) {
            "Group ID must be provided"
        }.let(::AccountId)

        Log.d(TAG, "Group leaving work started for $groupId")

        return groupScope.launchAndWait(groupId, "GroupLeavingWorker") {
            val group = configFactory.getGroup(groupId)

            // Make sure we only have one group leaving control message
            storage.deleteGroupInfoMessages(groupId, UpdateMessageData.Kind.GroupLeaving::class.java)
            storage.insertGroupInfoLeaving(groupId)

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

                        // Always send a "XXX left" message to the group if we can
                        MessageSender.send(
                            GroupUpdated(
                                GroupUpdateMessage.newBuilder()
                                    .setMemberLeftNotificationMessage(DataMessage.GroupUpdateMemberLeftNotificationMessage.getDefaultInstance())
                                    .build()
                            ),
                            address
                        )

                        // If we are not the only admin, send a left message for other admin to handle the member removal
                        // We'll have to wait for this message to be sent before going ahead to delete the group
                        val statusChannel = Channel<kotlin.Result<Unit>>()
                        MessageSender.send(
                            GroupUpdated(
                                GroupUpdateMessage.newBuilder()
                                    .setMemberLeftMessage(DataMessage.GroupUpdateMemberLeftMessage.getDefaultInstance())
                                    .build()
                            ),
                            address,
                            statusCallback = statusChannel
                        )

                        statusChannel.receive().getOrThrow()
                    }

                    // If we are the only admin, leaving this group will destroy the group
                    if (weAreTheOnlyAdmin) {
                        configFactory.withMutableGroupConfigs(groupId) { configs ->
                            configs.groupInfo.destroyGroup()
                        }

                        // Must wait until the config is pushed, otherwise if we go through the rest
                        // of the code it will destroy the conversation, destroying the necessary configs
                        // along the way, we won't be able to push the "destroyed" state anymore.
                        configFactory.waitUntilGroupConfigsPushed(groupId, timeoutMills = 0L)
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

        fun schedule(context: Context, groupId: AccountId) {
            WorkManager.getInstance(context)
                .enqueue(
                    OneTimeWorkRequestBuilder<GroupLeavingWorker>()
                        .addTag(KEY_GROUP_ID)
                        .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                        .setInputData(
                            Data.Builder().putString(KEY_GROUP_ID, groupId.hexString).build()
                        )
                        .build()
                )
        }
    }
}
