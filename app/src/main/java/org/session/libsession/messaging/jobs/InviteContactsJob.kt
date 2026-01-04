package org.session.libsession.messaging.jobs

import android.widget.Toast
import com.google.protobuf.ByteString
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.ED25519
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.groups.GroupInviteException
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.messaging.utilities.MessageAuthentication.buildGroupInviteSignature
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.withGroupConfigs
import org.session.libsession.utilities.withMutableGroupConfigs
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import org.session.protos.SessionProtos.GroupUpdateInviteMessage
import org.session.protos.SessionProtos.GroupUpdateMessage

class InviteContactsJob @AssistedInject constructor(
    @Assisted val groupSessionId: String,
    @Assisted val memberSessionIds: Array<String>,
    @Assisted val isReinvite: Boolean,
    private val configFactory: ConfigFactoryProtocol,
    private val messageSender: MessageSender,

) : Job {

    companion object {
        const val KEY = "InviteContactJob"
        private const val GROUP = "group"
        private const val MEMBER = "member"
        private const val REINVITE = "reinvite"

    }

    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0
    override val maxFailureCount: Int = 1

    override suspend fun execute(dispatcherName: String) {
        val group = requireNotNull(configFactory.getGroup(AccountId(groupSessionId))) {
            "Group must exist to invite"
        }

        val adminKey = requireNotNull(group.adminKey) {
            "User must be admin of group to invite"
        }

        val sessionId = AccountId(groupSessionId)

        coroutineScope {
            val requests = memberSessionIds.map { memberSessionId ->
                async {
                    runCatching {
                        // Make the request for this member
                        val memberId = AccountId(memberSessionId)
                        val (groupName, subAccount) = configFactory.withMutableGroupConfigs(sessionId) { configs ->
                            configs.groupInfo.getName() to configs.groupKeys.makeSubAccount(memberSessionId)
                        }

                        val timestamp = SnodeAPI.nowWithOffset
                        val signature = ED25519.sign(
                            ed25519PrivateKey = adminKey.data,
                            message = buildGroupInviteSignature(memberId, timestamp),
                        )

                        val groupInvite = GroupUpdateInviteMessage.newBuilder()
                            .setGroupSessionId(groupSessionId)
                            .setMemberAuthData(ByteString.copyFrom(subAccount))
                            .setAdminSignature(ByteString.copyFrom(signature))
                            .setName(groupName)
                        val message = GroupUpdateMessage.newBuilder()
                            .setInviteMessage(groupInvite)
                            .build()
                        val update = GroupUpdated(message).apply {
                            sentTimestamp = timestamp
                        }

                        messageSender.sendNonDurably(update, Destination.Contact(memberSessionId), false)
                    }
                }
            }

            val results = memberSessionIds.zip(requests.awaitAll())

            configFactory.withMutableGroupConfigs(sessionId) { configs ->
                results.forEach { (memberSessionId, result) ->
                    configs.groupMembers.get(memberSessionId)?.let { member ->
                        if (result.isFailure) {
                            member.setInviteFailed()
                        } else {
                            member.setInviteSent()
                        }
                        configs.groupMembers.set(member)
                    }
                }
            }

            val groupName = configFactory.withGroupConfigs(sessionId) { it.groupInfo.getName() }
                ?: configFactory.getGroup(sessionId)?.name

            // Gather all the exceptions, while keeping track of the invitee account IDs
            val failures = results.mapNotNull { (id, result) ->
                result.exceptionOrNull()?.let { err -> id to err }
            }

            // if there are failed invites, display a message
            // assume job "success" even if we fail, the state of invites is tracked outside of this job
            if (failures.isNotEmpty()) {
                // show the failure toast
                val (_, firstError) = failures.first()

                // Add the rest of the exceptions as suppressed
                for ((_, suppressed) in failures.asSequence().drop(1)) {
                    firstError.addSuppressed(suppressed)
                }

                Log.w("InviteContactsJob", "Failed to invite contacts", firstError)

                GroupInviteException(
                    isPromotion = false,
                    inviteeAccountIds = failures.map { it.first },
                    groupName = groupName.orEmpty(),
                    underlying = firstError,
                    isReinvite = isReinvite
                ).format(
                    MessagingModuleConfiguration.shared.context,
                    MessagingModuleConfiguration.shared.recipientRepository
                ).let {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            MessagingModuleConfiguration.shared.context,
                            it,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    override fun serialize(): Data =
        Data.Builder()
            .putString(GROUP, groupSessionId)
            .putStringArray(MEMBER, memberSessionIds)
            .putBoolean(REINVITE, isReinvite)
            .build()

    override fun getFactoryKey(): String = KEY

    @AssistedFactory
    abstract class Factory : Job.DeserializeFactory<InviteContactsJob> {
        abstract fun create(
            groupSessionId: String,
            memberSessionIds: Array<String>,
            isReinvite: Boolean
        ): InviteContactsJob

        override fun create(data: Data): InviteContactsJob? {
            val groupSessionId = data.getString(GROUP) ?: return null
            val memberSessionIds = data.getStringArray(MEMBER) ?: return null
            val reinvite = data.getBooleanOrDefault(REINVITE, false)
            return create(
                groupSessionId = groupSessionId,
                memberSessionIds = memberSessionIds,
                isReinvite = reinvite
            )
        }
    }
}