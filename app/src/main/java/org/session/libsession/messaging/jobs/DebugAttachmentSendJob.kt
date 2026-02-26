package org.session.libsession.messaging.jobs

import android.app.Application
import android.net.Uri
import android.util.Log
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import network.loki.messenger.BuildConfig
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.messaging.messages.applyExpiryMode
import org.session.libsession.messaging.messages.signal.OutgoingMediaMessage
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.mms.GifSlide
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.mms.VideoSlide
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.util.MediaUtil

class DebugAttachmentSendJob @AssistedInject constructor(
    @Assisted("threadId") private val threadId: Long,
    @Assisted("address") private val addressSerialized: String,
    @Assisted("count") private val count: Int,
    @Assisted("delayMs") private val delayBetweenSendsMs: Long,
    @Assisted("prefix") private val prefix: String,
    @Assisted("body") private val body: String?,
    @Assisted("mediaSpecs") private val mediaSpecsBytes: ByteArray,

    private val application: Application,
    private val recipientRepository: RecipientRepository,
    private val proStatusManager: ProStatusManager,
    private val messageSender: MessageSender,
    private val snodeClock: SnodeClock,
    private val mmsDb: MmsDatabase,
) : Job {

    data class MediaSpec(
        val uriString: String = "",
        val mimeType: String = "",
        val filename: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val caption: String? = null
    )

    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0
    override val maxFailureCount: Int = 3

    companion object {
        const val KEY = "DebugAttachmentSendJob"

        private const val THREAD_ID_KEY = "thread_id"
        private const val ADDRESS_KEY = "address"
        private const val COUNT_KEY = "count"
        private const val DELAY_KEY = "delay_ms"
        private const val PREFIX_KEY = "prefix"
        private const val BODY_KEY = "body"
        private const val MEDIA_SPECS_KEY = "media_specs"

        private const val MAX_BUFFER_SIZE_BYTES = 1_000_000 // ~1MB

        private fun encodeSpecs(specs: List<MediaSpec>): ByteArray {
            val kryo = Kryo().apply { isRegistrationRequired = false }
            val output = Output(ByteArray(4096), MAX_BUFFER_SIZE_BYTES)
            kryo.writeClassAndObject(output, specs)
            output.close()
            return output.toBytes()
        }

        @Suppress("UNCHECKED_CAST")
        private fun decodeSpecs(bytes: ByteArray): List<MediaSpec> {
            val kryo = Kryo().apply { isRegistrationRequired = false }
            val input = Input(bytes)
            val obj = kryo.readClassAndObject(input)
            input.close()
            return (obj as? List<MediaSpec>).orEmpty()
        }
    }

    override suspend fun execute(dispatcherName: String) {
        if (!BuildConfig.DEBUG) {
            delegate?.handleJobFailedPermanently(this, dispatcherName, IllegalStateException("Debug-only job"))
            return
        }

        val address = Address.fromSerialized(addressSerialized) as Address.Conversable
        val recipient = recipientRepository.getRecipientSync(address)

        val specs = decodeSpecs(mediaSpecsBytes)
        if (specs.isEmpty() || count <= 0) {
            delegate?.handleJobSucceeded(this, dispatcherName)
            return
        }

        repeat(count) { i ->
            val attachments = buildAttachments(specs)

            val sentTimestamp = snodeClock.currentTimeMillis() + i
            val message = VisibleMessage().applyExpiryMode(address).apply {
                this.sentTimestamp = sentTimestamp
                this.text = when {
                    body.isNullOrBlank() -> "$prefix (#${i + 1})"
                    else -> "$body (#${i + 1})"
                }
            }

            proStatusManager.addProFeatures(message)

            val expiresInMs = recipient.expiryMode.expiryMillis ?: 0L
            val expireStartedAtMs = if (recipient.expiryMode is ExpiryMode.AfterSend) sentTimestamp else 0L

            val outgoing = OutgoingMediaMessage(
                message = message,
                recipient = recipient.address,
                attachments = attachments,
                outgoingQuote = null,
                linkPreview = null,
                expiresInMillis = expiresInMs,
                expireStartedAt = expireStartedAtMs
            )

            message.id = MessageId(
                mmsDb.insertMessageOutbox(
                    outgoing,
                    threadId,
                    false,
                    runThreadUpdate = true
                ),
                mms = true
            )

            Log.d(KEY, "DebugAttachmentSendJob send #${i + 1}/${count} attachments=${attachments.size}")
            messageSender.send(message, recipient.address, null, null)

            if (delayBetweenSendsMs > 0) delay(delayBetweenSendsMs)
        }

        delegate?.handleJobSucceeded(this, dispatcherName)
    }

    private fun buildAttachments(specs: List<MediaSpec>): List<Attachment> {
        val slideDeck = SlideDeck()
        val ctx = application

        for (s in specs) {
            val uri = Uri.parse(s.uriString)
            when {
                MediaUtil.isVideoType(s.mimeType) ->
                    slideDeck.addSlide(VideoSlide(ctx, uri, s.filename, 0, s.caption))
                MediaUtil.isGif(s.mimeType) ->
                    slideDeck.addSlide(
                        GifSlide(
                            ctx,
                            uri,
                            s.filename,
                            0,
                            s.width,
                            s.height,
                            s.caption
                        )
                    )
                MediaUtil.isImageType(s.mimeType) ->
                    slideDeck.addSlide(
                        ImageSlide(
                            ctx,
                            uri,
                            s.filename,
                            0,
                            s.width,
                            s.height,
                            s.caption
                        )
                    )
                else -> {
                    // ignore unsupported types for now
                }
            }
        }

        return slideDeck.asAttachments()
    }

    override fun serialize(): Data {
        return Data.Builder()
            .putLong(THREAD_ID_KEY, threadId)
            .putString(ADDRESS_KEY, addressSerialized)
            .putInt(COUNT_KEY, count)
            .putLong(DELAY_KEY, delayBetweenSendsMs)
            .putString(PREFIX_KEY, prefix)
            .putString(BODY_KEY, body!!)
            .putByteArray(MEDIA_SPECS_KEY, mediaSpecsBytes)
            .build()
    }

    override fun getFactoryKey(): String = KEY

    @AssistedFactory
    interface Factory : Job.DeserializeFactory<DebugAttachmentSendJob> {
        fun create(
            @Assisted("threadId") threadId: Long,
            @Assisted("address") addressSerialized: String,
            @Assisted("count") count: Int,
            @Assisted("delayMs") delayBetweenSendsMs: Long,
            @Assisted("prefix") prefix: String,
            @Assisted("body") body: String?,
            @Assisted("mediaSpecs") mediaSpecsBytes: ByteArray,
        ): DebugAttachmentSendJob

        fun create(
            threadId: Long,
            address: Address.Conversable,
            count: Int,
            delayBetweenSendsMs: Long,
            prefix: String,
            body: String?,
            mediaSpecs: List<MediaSpec>,
        ): DebugAttachmentSendJob = create(
            threadId = threadId,
            addressSerialized = address.toString(),
            count = count,
            delayBetweenSendsMs = delayBetweenSendsMs,
            prefix = prefix,
            body = body,
            mediaSpecsBytes = encodeSpecs(mediaSpecs)
        )

        override fun create(data: Data): DebugAttachmentSendJob? {
            return create(
                threadId = data.getLong(THREAD_ID_KEY),
                addressSerialized = data.getString(ADDRESS_KEY)!!,
                count = data.getInt(COUNT_KEY),
                delayBetweenSendsMs = data.getLong(DELAY_KEY),
                prefix = data.getString(PREFIX_KEY)!!,
                body = data.getString(BODY_KEY),
                mediaSpecsBytes = data.getByteArray(MEDIA_SPECS_KEY)!!
            )
        }
    }
}