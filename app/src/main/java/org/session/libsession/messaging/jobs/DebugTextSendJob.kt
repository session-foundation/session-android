package org.session.libsession.messaging.jobs

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import network.loki.messenger.BuildConfig
import org.session.libsession.messaging.messages.applyExpiryMode
import org.session.libsession.messaging.messages.signal.OutgoingTextMessage
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.model.MessageId

class DebugTextSendJob @AssistedInject constructor(
    @Assisted("threadId") private val threadId: Long,
    @Assisted("address") private val addressSerialized: String,
    @Assisted("count") private val count: Int,
    @Assisted("delayMs") private val delayBetweenMessagesMs: Long,
    @Assisted("prefix") private val prefix: String,

    private val smsDb: SmsDatabase,
    private val messageSender: MessageSender,
    private val snodeClock: SnodeClock,
) : Job {

    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0

    override val maxFailureCount: Int = 3

    companion object {
        const val KEY = "DebugTextSendJob"

        private const val THREAD_ID_KEY = "thread_id"
        private const val ADDRESS_KEY = "address"
        private const val COUNT_KEY = "count"
        private const val DELAY_KEY = "delay_ms"
        private const val PREFIX_KEY = "prefix"
    }

    override suspend fun execute(dispatcherName: String) {
        if (!BuildConfig.DEBUG) {
            // Safety guard
            delegate?.handleJobFailedPermanently(this, dispatcherName, IllegalStateException("Debug-only job"))
            return
        }

        val address = Address.fromSerialized(addressSerialized)

        repeat(count) { i ->
            val ts = snodeClock.currentTimeMillis() + i

            val message = VisibleMessage().applyExpiryMode(address).apply {
                sentTimestamp = ts
                text = "$prefix #${i + 1}"
            }

            val outgoing = OutgoingTextMessage(
                message = message,
                recipient = address,
                expiresInMillis = 0,
                expireStartedAtMillis = 0
            )

            message.id = MessageId(
                smsDb.insertMessageOutbox(
                    threadId,
                    outgoing,
                    false,
                    message.sentTimestamp!!,
                    true
                ),
                false
            )

            messageSender.send(message, address)

            if (delayBetweenMessagesMs > 0) delay(delayBetweenMessagesMs)
        }
    }

    override fun serialize(): Data {
        return Data.Builder()
            .putLong(THREAD_ID_KEY, threadId)
            .putString(ADDRESS_KEY, addressSerialized)
            .putInt(COUNT_KEY, count)
            .putLong(DELAY_KEY, delayBetweenMessagesMs)
            .putString(PREFIX_KEY, prefix)
            .build()
    }

    override fun getFactoryKey(): String = KEY

    @AssistedFactory
    abstract class Factory : Job.DeserializeFactory<DebugTextSendJob> {
        abstract fun create(
            @Assisted("threadId") threadId: Long,
            @Assisted("address") addressSerialized: String,
            @Assisted("count") count: Int,
            @Assisted("delayMs") delayBetweenMessagesMs: Long,
            @Assisted("prefix") prefix: String,
        ): DebugTextSendJob

        fun create(
            threadId: Long,
            address: Address,
            count: Int,
            delayBetweenMessagesMs: Long,
            prefix: String,
        ): DebugTextSendJob = create(
            threadId = threadId,
            addressSerialized = address.toString(),
            count = count,
            delayBetweenMessagesMs = delayBetweenMessagesMs,
            prefix = prefix
        )

        override fun create(data: Data): DebugTextSendJob? {
            return create(
                threadId = data.getLong(THREAD_ID_KEY),
                addressSerialized = data.getString(ADDRESS_KEY)!!,
                count = data.getInt(COUNT_KEY),
                delayBetweenMessagesMs = data.getLong(DELAY_KEY),
                prefix = data.getString(PREFIX_KEY)!!,
            )
        }
    }
}