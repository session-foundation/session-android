package org.session.libsession.messaging.jobs

import android.content.Context
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase

class TrimThreadJob @AssistedInject constructor(
    @Assisted val threadId: Long,
    private val storage: StorageProtocol,
    @param:ApplicationContext private val context: Context,
    threadDatabase: ThreadDatabase,
) : Job {
    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0

    override val maxFailureCount: Int = 1

    companion object {
        const val KEY: String = "TrimThreadJob"
        const val THREAD_ID = "thread_id"

        const val TRIM_TIME_LIMIT = 15552000000L // trim messages older than this
        const val THREAD_LENGTH_TRIGGER_SIZE = 2000
    }

    val communityAddress: Address.Community? = threadDatabase.getRecipientForThreadId(threadId) as? Address.Community

    override suspend fun execute(dispatcherName: String) {
        val trimmingEnabled = TextSecurePreferences.isThreadLengthTrimmingEnabled(context)
        val messageCount = storage.getMessageCount(threadId)
        if (trimmingEnabled && messageCount >= THREAD_LENGTH_TRIGGER_SIZE) {
            val oldestMessageTime = System.currentTimeMillis() - TRIM_TIME_LIMIT
            storage.trimThreadBefore(threadId, oldestMessageTime)
        }
        delegate?.handleJobSucceeded(this, dispatcherName)
    }

    override fun serialize(): Data {
        val builder = Data.Builder()
            .putLong(THREAD_ID, threadId)

        return builder.build()
    }

    override fun getFactoryKey(): String = "TrimThreadJob"

    @AssistedFactory
    abstract class Factory : Job.DeserializeFactory<TrimThreadJob> {

        override fun create(data: Data): TrimThreadJob {
            return create(data.getLong(THREAD_ID))
        }

        abstract fun create(threadId: Long): TrimThreadJob
    }

}