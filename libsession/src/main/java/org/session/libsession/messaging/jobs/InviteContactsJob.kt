package org.session.libsession.messaging.jobs

import android.widget.Toast
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.session.libsession.R
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.messaging.utilities.MessageAuthentication.buildGroupInviteSignature
import org.session.libsession.messaging.utilities.SodiumUtilities
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.OTHER_NAME_KEY
import org.session.libsession.utilities.truncateIdForDisplay
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateInviteMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateMessage
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.prettifiedDescription

class InviteContactsJob(val groupSessionId: String, val memberSessionIds: Array<String>) : Job {

    companion object {
        const val KEY = "InviteContactJob"
        private const val GROUP = "group"
        private const val MEMBER = "member"

    }

    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0
    override val maxFailureCount: Int = 1

    override suspend fun execute(dispatcherName: String) {
        val delegate = delegate ?: return
        val configs = MessagingModuleConfiguration.shared.configFactory
        val adminKey = configs.userGroups?.getClosedGroup(groupSessionId)?.adminKey
            ?: return delegate.handleJobFailedPermanently(
                this,
                dispatcherName,
                NullPointerException("No admin key")
            )

        withContext(Dispatchers.IO) {
            val sessionId = AccountId(groupSessionId)
            val members = configs.getGroupMemberConfig(sessionId)
            val info = configs.getGroupInfoConfig(sessionId)
            val keys = configs.getGroupKeysConfig(sessionId, info, members, free = false)

            if (members == null || info == null || keys == null) {
                return@withContext delegate.handleJobFailedPermanently(
                    this@InviteContactsJob,
                    dispatcherName,
                    NullPointerException("One of the group configs was null")
                )
            }

            val requests = memberSessionIds.map { memberSessionId ->
                async {
                    // Make the request for this member
                    val member = members.get(memberSessionId) ?: return@async run {
                        InviteResult.failure(
                            memberSessionId,
                            NullPointerException("No group member ${memberSessionId.prettifiedDescription()} in members config")
                        )
                    }
                    members.set(member.setInvited())
                    configs.saveGroupConfigs(keys, info, members)

                    val accountId = AccountId(memberSessionId)
                    val subAccount = keys.makeSubAccount(accountId)

                    val timestamp = SnodeAPI.nowWithOffset
                    val signature = SodiumUtilities.sign(
                        buildGroupInviteSignature(accountId, timestamp),
                        adminKey
                    )

                    val groupInvite = GroupUpdateInviteMessage.newBuilder()
                        .setGroupSessionId(groupSessionId)
                        .setMemberAuthData(ByteString.copyFrom(subAccount))
                        .setAdminSignature(ByteString.copyFrom(signature))
                        .setName(info.getName())
                    val message = GroupUpdateMessage.newBuilder()
                        .setInviteMessage(groupInvite)
                        .build()
                    val update = GroupUpdated(message).apply {
                        sentTimestamp = timestamp
                    }
                    try {
                        MessageSender.send(update, Destination.Contact(memberSessionId), false)
                            .get()
                        InviteResult.success(memberSessionId)
                    } catch (e: Exception) {
                        InviteResult.failure(memberSessionId, e)
                    }
                }
            }
            val results = requests.awaitAll()
            results.forEach { result ->
                if (!result.success) {
                    // update invite failed
                    val toSet = members.get(result.memberSessionId)
                        ?.setInviteFailed()
                        ?: return@forEach
                    members.set(toSet)
                }
            }
            val failures = results.filter { !it.success }
            // if there are failed invites, display a message
            // assume job "success" even if we fail, the state of invites is tracked outside of this job
            if (failures.isNotEmpty()) {
                // show the failure toast
                val storage = MessagingModuleConfiguration.shared.storage
                val toaster = MessagingModuleConfiguration.shared.toaster
                when (failures.size) {
                    1 -> {
                        val first = failures.first()
                        val firstString = first.memberSessionId.let { storage.getContactWithAccountID(it) }?.name
                            ?: truncateIdForDisplay(first.memberSessionId)
                        withContext(Dispatchers.Main) {
                            toaster.toast(R.string.groupInviteFailedUser, Toast.LENGTH_LONG,
                                mapOf(
                                    NAME_KEY to firstString,
                                    GROUP_NAME_KEY to info.getName()
                                )
                            )
                        }
                    }
                    2 -> {
                        val (first, second) = failures
                        val firstString = first.memberSessionId.let { storage.getContactWithAccountID(it) }?.name
                            ?: truncateIdForDisplay(first.memberSessionId)
                        val secondString = second.memberSessionId.let { storage.getContactWithAccountID(it) }?.name
                            ?: truncateIdForDisplay(second.memberSessionId)

                        withContext(Dispatchers.Main) {
                            toaster.toast(R.string.groupInviteFailedTwo, Toast.LENGTH_LONG,
                                mapOf(
                                    NAME_KEY to firstString,
                                    OTHER_NAME_KEY to secondString,
                                    GROUP_NAME_KEY to info.getName()
                                )
                            )
                        }
                    }
                    else -> {
                        val first = failures.first()
                        val firstString = first.memberSessionId.let { storage.getContactWithAccountID(it) }?.name
                            ?: truncateIdForDisplay(first.memberSessionId)
                        val remaining = failures.size - 1
                        withContext(Dispatchers.Main) {
                            toaster.toast(R.string.groupInviteFailedMultiple, Toast.LENGTH_LONG,
                                mapOf(
                                    NAME_KEY to firstString,
                                    OTHER_NAME_KEY to remaining.toString(),
                                    GROUP_NAME_KEY to info.getName()
                                )
                            )
                        }
                    }
                }
            }
            configs.saveGroupConfigs(keys, info, members)
            keys.free()
            info.free()
            members.free()
        }
    }

    @Suppress("DataClassPrivateConstructor")
    data class InviteResult private constructor(
        val memberSessionId: String,
        val success: Boolean,
        val error: Exception? = null
    ) {
        companion object {
            fun success(memberSessionId: String) = InviteResult(memberSessionId, success = true)
            fun failure(memberSessionId: String, error: Exception) =
                InviteResult(memberSessionId, success = false, error)
        }
    }

    override fun serialize(): Data =
        Data.Builder()
            .putString(GROUP, groupSessionId)
            .putStringArray(MEMBER, memberSessionIds)
            .build()

    override fun getFactoryKey(): String = KEY

}