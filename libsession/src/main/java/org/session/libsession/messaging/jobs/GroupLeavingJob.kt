package org.session.libsession.messaging.jobs

import kotlinx.coroutines.channels.SendChannel
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.control.ClosedGroupControlMessage
import org.session.libsession.messaging.sending_receiving.MessageReceiver
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.sending_receiving.disableLocalGroupAndUnsubscribe
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log

@Deprecated("This job is only applicable for legacy group. For new group, call GroupManagerV2.leaveGroup directly.")
class GroupLeavingJob(
    val groupPublicKey: String,
    // Channel to send the result of the job to. This field won't be persisted
    private val completeChannel: SendChannel<Result<Unit>>?,
    val deleteThread: Boolean): Job {

    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0

    override val maxFailureCount: Int = 0

    companion object {
        val TAG = GroupLeavingJob::class.simpleName
        val KEY: String = "GroupLeavingJob"

        // Keys used for database storage
        private val GROUP_PUBLIC_KEY_KEY = "group_public_key"
        private val DELETE_THREAD_KEY = "delete_thread"
    }

    override suspend fun execute(dispatcherName: String) {
        val context = MessagingModuleConfiguration.shared.context
        val storage = MessagingModuleConfiguration.shared.storage
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)!!
        val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
        val group = storage.getGroup(groupID) ?: return handlePermanentFailure(dispatcherName, MessageSender.Error.NoThread)
        val updatedMembers = group.members.map { it.serialize() }.toSet() - userPublicKey
        val admins = group.admins.map { it.serialize() }
        val name = group.title
        // Send the update to the group
        val closedGroupControlMessage = ClosedGroupControlMessage(ClosedGroupControlMessage.Kind.MemberLeft())
        val sentTime = SnodeAPI.nowWithOffset
        closedGroupControlMessage.sentTimestamp = sentTime
        storage.setActive(groupID, false)

        MessageSender.sendNonDurably(closedGroupControlMessage, Address.fromSerialized(groupID), false).success {
            // Notify the user
            completeChannel?.trySend(Result.success(Unit))

            // Remove the group private key and unsubscribe from PNs
            MessageReceiver.disableLocalGroupAndUnsubscribe(groupPublicKey, groupID, userPublicKey, deleteThread)
            handleSuccess(dispatcherName)
        }.fail {
            storage.setActive(groupID, true)

            // Notify the user
            completeChannel?.trySend(Result.failure(it))
            handleFailure(dispatcherName, it)
        }
    }

    private fun handleSuccess(dispatcherName: String) {
        Log.w(TAG, "Group left successfully.")
        delegate?.handleJobSucceeded(this, dispatcherName)
    }

    private fun handlePermanentFailure(dispatcherName: String, e: Exception) {
        delegate?.handleJobFailedPermanently(this, dispatcherName, e)
    }

    private fun handleFailure(dispatcherName: String, e: Exception) {
        delegate?.handleJobFailed(this, dispatcherName, e)
    }

    override fun serialize(): Data {
        return Data.Builder()
                .putString(GROUP_PUBLIC_KEY_KEY, groupPublicKey)
                .putBoolean(DELETE_THREAD_KEY, deleteThread)
                .build()
    }

    override fun getFactoryKey(): String {
        return KEY
    }

    class Factory : Job.Factory<GroupLeavingJob> {

        override fun create(data: Data): GroupLeavingJob {
            return GroupLeavingJob(
                groupPublicKey = data.getString(GROUP_PUBLIC_KEY_KEY),
                completeChannel = null,
                deleteThread = data.getBoolean(DELETE_THREAD_KEY)
            )
        }
    }
}