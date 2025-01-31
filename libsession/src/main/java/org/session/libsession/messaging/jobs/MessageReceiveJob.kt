package org.session.libsession.messaging.jobs

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Inject
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.sending_receiving.MessageReceiver
import org.session.libsession.messaging.sending_receiving.handle
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log

// Keys used for database storage
private const val DATA_KEY = "data"
private const val SERVER_HASH_KEY = "serverHash"
private const val OPEN_GROUP_MESSAGE_SERVER_ID_KEY = "openGroupMessageServerID"
private const val OPEN_GROUP_ID_KEY = "open_group_id"

class MessageReceiveJob @AssistedInject constructor(
    @Assisted(DATA_KEY)                         val data: ByteArray,
    @Assisted(SERVER_HASH_KEY)                  val serverHash: String? = null,
    @Assisted(OPEN_GROUP_MESSAGE_SERVER_ID_KEY) val openGroupMessageServerID: Long? = null,
    @Assisted(OPEN_GROUP_ID_KEY)                val openGroupID: String? = null,
                                                val textSecurePreferences: TextSecurePreferences // Injected
) : Job {
    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0

    override val maxFailureCount: Int = 10

    companion object {
        val TAG = MessageReceiveJob::class.simpleName
        val KEY: String = "MessageReceiveJob"
    }

    override suspend fun execute(dispatcherName: String) = executeAsync(dispatcherName).await()

    fun executeAsync(dispatcherName: String): Promise<Unit, Exception> {
        val deferred = deferred<Unit, Exception>()
        try {
            val storage = MessagingModuleConfiguration.shared.storage
            val serverPublicKey = openGroupID?.let {
                storage.getOpenGroupPublicKey(it.split(".").dropLast(1).joinToString("."))
            }
            val currentClosedGroups = storage.getAllActiveClosedGroupPublicKeys()
            val (message, proto) = MessageReceiver.parse(this.data, this.openGroupMessageServerID, openGroupPublicKey = serverPublicKey, currentClosedGroups = currentClosedGroups)
            val threadId = Message.getThreadId(message, this.openGroupID, storage, false)
            message.serverHash = serverHash
            MessageReceiver.handle(message, proto, threadId ?: -1, this.openGroupID, null, textSecurePreferences)
            this.handleSuccess(dispatcherName)
            deferred.resolve(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't receive message.", e)
            if (e is MessageReceiver.Error && !e.isRetryable) {
                Log.e("Loki", "Message receive job permanently failed.", e)
                this.handlePermanentFailure(dispatcherName, e)
            } else {
                Log.e("Loki", "Couldn't receive message.", e)
                this.handleFailure(dispatcherName, e)
            }
            deferred.resolve(Unit) // The promise is just used to keep track of when we're done
        }
        return deferred.promise
    }

    private fun handleSuccess(dispatcherName: String) {
        delegate?.handleJobSucceeded(this, dispatcherName)
    }

    private fun handlePermanentFailure(dispatcherName: String, e: Exception) {
        delegate?.handleJobFailedPermanently(this, dispatcherName, e)
    }

    private fun handleFailure(dispatcherName: String, e: Exception) {
        delegate?.handleJobFailed(this, dispatcherName, e)
    }

    override fun serialize(): Data {
        val builder = Data.Builder().putByteArray(DATA_KEY, data)
        serverHash?.let { builder.putString(SERVER_HASH_KEY, it) }
        openGroupMessageServerID?.let { builder.putLong(OPEN_GROUP_MESSAGE_SERVER_ID_KEY, it) }
        openGroupID?.let { builder.putString(OPEN_GROUP_ID_KEY, it) }
        return builder.build();
    }

    override fun getFactoryKey(): String = KEY

    @AssistedFactory
    interface MessageReceiveJobAssistedFactory {
        // Note: We need the identifiers on the "@Assisted" processors because there are multiple
        // arguments with the same type (we have to do the same on the actual constructor as well).
        fun create(
            @Assisted(DATA_KEY)   data: ByteArray,
            @Assisted(SERVER_HASH_KEY)serverHash: String? = null,
            @Assisted(OPEN_GROUP_MESSAGE_SERVER_ID_KEY) openGroupMessageServerID: Long? = null,
            @Assisted(OPEN_GROUP_ID_KEY) openGroupID: String? = null
        ): MessageReceiveJob
    }

    class Factory @Inject constructor(
        private val assistedFactory: MessageReceiveJobAssistedFactory
    ): Job.Factory<MessageReceiveJob> {
        override fun create(data: Data): MessageReceiveJob {
            val dataArray = data.getByteArray(DATA_KEY)
            val serverHash = data.getStringOrDefault(SERVER_HASH_KEY, null)
            val openGroupMessageServerID = data.getLongOrDefault(OPEN_GROUP_MESSAGE_SERVER_ID_KEY, -1).let { if (it == -1L) null else it }
            val openGroupID = data.getStringOrDefault(OPEN_GROUP_ID_KEY, null)

            return assistedFactory.create(
                dataArray,
                serverHash,
                openGroupMessageServerID,
                openGroupID
            )
        }
    }
}