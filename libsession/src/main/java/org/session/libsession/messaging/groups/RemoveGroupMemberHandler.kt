package org.session.libsession.messaging.groups

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import network.loki.messenger.libsession_util.ReadableGroupKeysConfig
import network.loki.messenger.libsession_util.util.GroupMember
import network.loki.messenger.libsession_util.util.Sodium
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.snode.OwnedSwarmAuth
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateMessage
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.Namespace
import javax.inject.Inject

private const val TAG = "RemoveGroupMemberHandler"

private const val MIN_PROCESS_INTERVAL_MILLS = 1_000L

class RemoveGroupMemberHandler @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val textSecurePreferences: TextSecurePreferences,
) {
    private val scope: CoroutineScope = GlobalScope
    private var job: Job? = null

    fun start() {
        require(job == null) { "Already started" }

        job = scope.launch {
            while (true) {
                // Make sure we have a local number before we start processing
                textSecurePreferences.watchLocalNumber().first { it != null }

                val processStartedAt = SystemClock.uptimeMillis()

                try {
                    processPendingMemberRemoval()
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing pending member removal", e)
                }

                configFactory.configUpdateNotifications.firstOrNull()

                // Make sure we don't process too often. As some of the config changes don't apply
                // to us, but we have no way to tell if it does or not. The safest way is to process
                // everytime any config changes, with a minimum interval.
                val delayMills =
                    MIN_PROCESS_INTERVAL_MILLS - (SystemClock.uptimeMillis() - processStartedAt)

                if (delayMills > 0) {
                    delay(delayMills)
                }
            }
        }
    }

    private suspend fun processPendingMemberRemoval() {
        // Run the removal process for each group in parallel
        val removalTasks = configFactory.withUserConfigs { it.userGroups.allClosedGroupInfo() }
            .asSequence()
            .filter { it.hasAdminKey() }
            .associate { group ->
                group.name to scope.async {
                    processPendingRemovalsForGroup(
                        groupAccountId = group.groupAccountId,
                        groupName = group.name,
                        adminKey = group.adminKey!!
                    )
                }
            }

        // Wait and collect the results of the removal tasks
        for ((groupName, task) in removalTasks) {
            try {
                task.await()
            } catch (e: Exception) {
                Log.e(TAG, "Error processing pending removals for group $groupName", e)
            }
        }
    }

    private suspend fun processPendingRemovalsForGroup(
        groupAccountId: AccountId,
        groupName: String,
        adminKey: ByteArray
    ) {
        val swarmAuth = OwnedSwarmAuth(
            accountId = groupAccountId,
            ed25519PublicKeyHex = null,
            ed25519PrivateKey = adminKey
        )

        val batchCalls = configFactory.withGroupConfigs(groupAccountId) { configs ->
            val pendingRemovals = configs.groupMembers.all().filter { it.removed }
            if (pendingRemovals.isEmpty()) {
                // Skip if there are no pending removals
                return@withGroupConfigs emptyList()
            }

            Log.d(TAG, "Processing ${pendingRemovals.size} pending removals for group $groupName")

            // Perform a sequential call to group snode to:
            // 1. Revoke the member's sub key (by adding the key to a "revoked list" under the hood)
            // 2. Send a message to a special namespace to inform the removed members they have been removed
            // 3. Conditionally, delete removed-members' messages from the group's message store, if that option is selected by the actioning admin
            val calls = ArrayList<SnodeAPI.SnodeBatchRequestInfo>(3)

            // Call No 1. Revoke sub-key. This call is crucial and must not fail for the rest of the operation to be successful.
            calls += checkNotNull(
                SnodeAPI.buildAuthenticatedRevokeSubKeyBatchRequest(
                    groupAdminAuth = swarmAuth,
                    subAccountTokens = pendingRemovals.map {
                        configs.groupKeys.getSubAccountToken(AccountId(it.sessionId))
                    }
                )
            ) { "Fail to create a revoke request" }

            // Call No 2. Send a message to the removed members
            calls += SnodeAPI.buildAuthenticatedStoreBatchInfo(
                namespace = Namespace.REVOKED_GROUP_MESSAGES(),
                message = buildGroupKickMessage(groupAccountId.hexString, pendingRemovals, configs.groupKeys, adminKey),
                auth = swarmAuth,
            )

            // Call No 3. Conditionally remove the message from the group's message store
            if (pendingRemovals.any { it.shouldRemoveMessages }) {
                calls += SnodeAPI.buildAuthenticatedStoreBatchInfo(
                    namespace = Namespace.CLOSED_GROUP_MESSAGES(),
                    message = buildDeleteGroupMemberContentMessage(
                        groupAccountId = groupAccountId.hexString,
                        memberSessionIDs = pendingRemovals
                            .asSequence()
                            .filter { it.shouldRemoveMessages }
                            .map { it.sessionId }
                    ),
                    auth = swarmAuth,
                )
            }

            calls
        }

        if (batchCalls.isEmpty()) {
            return
        }

        val node = SnodeAPI.getSingleTargetSnode(groupAccountId.hexString).await()
        SnodeAPI.getBatchResponse(node, groupAccountId.hexString, batchCalls, true)

        //TODO: Handle message removal
    }

    private fun buildDeleteGroupMemberContentMessage(
        groupAccountId: String,
        memberSessionIDs: Sequence<String>
    ): SnodeMessage {
        return MessageSender.buildWrappedMessageToSnode(
            destination = Destination.ClosedGroup(groupAccountId),
            message = GroupUpdated(
                GroupUpdateMessage.newBuilder()
                    .setDeleteMemberContent(
                        SignalServiceProtos.DataMessage.GroupUpdateDeleteMemberContentMessage
                            .newBuilder()
                            .apply {
                                for (id in memberSessionIDs) {
                                    addMemberSessionIds(id)
                                }
                            }
                    )
                    .build()
            ),
            isSyncMessage = false
        )
    }

    private fun buildGroupKickMessage(
        groupAccountId: String,
        pendingRemovals: List<GroupMember>,
        keys: ReadableGroupKeysConfig,
        adminKey: ByteArray
    ) = SnodeMessage(
        recipient = groupAccountId,
        data = Base64.encodeBytes(
            Sodium.encryptForMultipleSimple(
                messages = Array(pendingRemovals.size) {
                    "${pendingRemovals[it].sessionId}${keys.currentGeneration()}".encodeToByteArray()
                },
                recipients = Array(pendingRemovals.size) {
                    AccountId(pendingRemovals[it].sessionId).pubKeyBytes
                },
                ed25519SecretKey = adminKey,
                domain = Sodium.KICKED_DOMAIN
            )
        ),
        ttl = SnodeMessage.CONFIG_TTL,
        timestamp = SnodeAPI.nowWithOffset
    )
}

