package org.session.libsession.messaging.jobs

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.ThreadDatabase

/**
 * A job to delete open group messages given by their server IDs.
 */
class OpenGroupDeleteJob @AssistedInject constructor(
    @Assisted private val messageServerIds: LongArray,
    @Assisted private val threadId: Long,
    private val dataProvider: MessageDataProvider,
    threadDatabase: ThreadDatabase,
): Job {

    companion object {
        private const val TAG = "OpenGroupDeleteJob"
        const val KEY = "OpenGroupDeleteJob"
        private const val MESSAGE_IDS = "messageIds"
        private const val THREAD_ID = "threadId"
    }

    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0
    override val maxFailureCount: Int = 1

    val address: Address.Community? = threadDatabase.getRecipientForThreadId(threadId) as? Address.Community

    override suspend fun execute(dispatcherName: String) {
        val numberToDelete = messageServerIds.size
        Log.d(TAG, "About to attempt to delete $numberToDelete messages")

        // FIXME: This entire process should probably run in a transaction (with the attachment deletion happening only if it succeeded)
        try {
            val (smsMessages, mmsMessages) = dataProvider.getMessageIDs(messageServerIds.toList(), threadId)

            // Delete the SMS messages
            if (smsMessages.isNotEmpty()) {
                dataProvider.deleteMessages(smsMessages, true)
            }

            // Delete the MMS messages
            if (mmsMessages.isNotEmpty()) {
                dataProvider.deleteMessages(mmsMessages, false)
            }

            Log.d(TAG, "Deleted ${smsMessages.size + mmsMessages.size} messages successfully")
            delegate?.handleJobSucceeded(this, dispatcherName)
        }
        catch (e: Exception) {
            Log.w(TAG, "OpenGroupDeleteJob failed: $e")
            delegate?.handleJobFailed(this, dispatcherName, e)
        }
    }

    override fun serialize(): Data = Data.Builder()
        .putLongArray(MESSAGE_IDS, messageServerIds)
        .putLong(THREAD_ID, threadId)
        .build()

    override fun getFactoryKey(): String = KEY

    @AssistedFactory
    abstract class Factory: Job.DeserializeFactory<OpenGroupDeleteJob> {
        override fun create(data: Data): OpenGroupDeleteJob {
            return create(
                messageServerIds = data.getLongArray(MESSAGE_IDS),
                threadId = data.getLong(THREAD_ID)
            )
        }

        abstract fun create(messageServerIds: LongArray, threadId: Long): OpenGroupDeleteJob
    }

}