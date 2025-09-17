package org.session.libsession.messaging.jobs

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.map
import okio.Buffer
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.DecodedAudio
import org.session.libsession.utilities.InputStreamMediaDataSource
import org.session.libsession.utilities.UploadResult
import org.session.libsignal.messages.SignalServiceAttachmentStream
import org.session.libsignal.streams.AttachmentCipherOutputStream
import org.session.libsignal.streams.AttachmentCipherOutputStreamFactory
import org.session.libsignal.streams.DigestingRequestBody
import org.session.libsignal.streams.PaddingInputStream
import org.session.libsignal.streams.PlaintextOutputStreamFactory
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.PushAttachmentData
import org.session.libsignal.utilities.Util
import org.thoughtcrime.securesms.database.ThreadDatabase

class AttachmentUploadJob @AssistedInject constructor(
    @Assisted val attachmentID: Long,
    @Assisted("threadID") val threadID: String,
    @Assisted private val message: Message,
    @Assisted private val messageSendJobID: String,
    private val storage: StorageProtocol,
    private val messageDataProvider: MessageDataProvider,
    private val messageSendJobFactory: MessageSendJob.Factory,
    private val threadDatabase: ThreadDatabase,
) : Job {
    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0

    // Error
    internal sealed class Error(val description: String) : Exception(description) {
        object NoAttachment : Error("No such attachment.")
    }

    // Settings
    override val maxFailureCount: Int = 20

    companion object {
        val TAG = AttachmentUploadJob::class.simpleName
        val KEY: String = "AttachmentUploadJob"

        // Keys used for database storage
        private val ATTACHMENT_ID_KEY = "attachment_id"
        private val THREAD_ID_KEY = "thread_id"
        private val MESSAGE_KEY = "message"
        private val MESSAGE_SEND_JOB_ID_KEY = "message_send_job_id"
    }

    override suspend fun execute(dispatcherName: String) {
        try {
            val attachment = messageDataProvider.getScaledSignalAttachmentStream(attachmentID)
                ?: return handleFailure(dispatcherName, Error.NoAttachment)

            val threadAddress = threadDatabase.getRecipientForThreadId(threadID.toLong()) ?: return handlePermanentFailure(dispatcherName,
                RuntimeException("Thread doesn't exist"))

            if (threadAddress is Address.Community) {
                val keyAndResult = upload(attachment, threadAddress.serverUrl, false) {
                    OpenGroupApi.upload(it, threadAddress.room, threadAddress.serverUrl)
                }
                handleSuccess(dispatcherName, attachment, keyAndResult.first, keyAndResult.second)
            } else {
                val keyAndResult = upload(attachment, FileServerApi.FILE_SERVER_URL, true) {
                    FileServerApi.upload(it).map { it.fileId }
                }
                handleSuccess(dispatcherName, attachment, keyAndResult.first, keyAndResult.second)
            }
        } catch (e: java.lang.Exception) {
            if (e == Error.NoAttachment) {
                this.handlePermanentFailure(dispatcherName, e)
            } else {
                this.handleFailure(dispatcherName, e)
            }
        }
    }

    private suspend fun upload(attachment: SignalServiceAttachmentStream, server: String, encrypt: Boolean, upload: (ByteArray) -> Promise<String, Exception>): Pair<ByteArray, UploadResult> {
        // Key
        val key = if (encrypt) Util.getSecretBytes(64) else ByteArray(0)
        // Length
        val rawLength = attachment.length
        val length = if (encrypt) {
            val paddedLength = PaddingInputStream.getPaddedSize(rawLength)
            AttachmentCipherOutputStream.getCiphertextLength(paddedLength)
        } else {
            attachment.length
        }
        // In & out streams
        // PaddingInputStream adds padding as data is read out from it. AttachmentCipherOutputStream
        // encrypts as it writes data.
        val inputStream = if (encrypt) PaddingInputStream(attachment.inputStream, rawLength) else attachment.inputStream
        val outputStreamFactory = if (encrypt) AttachmentCipherOutputStreamFactory(key) else PlaintextOutputStreamFactory()
        // Create a digesting request body but immediately read it out to a buffer. Doing this makes
        // it easier to deal with inputStream and outputStreamFactory.
        val pad = PushAttachmentData(attachment.contentType, inputStream, length, outputStreamFactory)
        val contentType = "application/octet-stream"
        val drb = DigestingRequestBody(pad.data, pad.outputStreamFactory, contentType, pad.dataSize)
        Log.d("Loki", "File size: ${length.toDouble() / 1000} kb.")
        val b = Buffer()
        drb.writeTo(b)
        val data = b.readByteArray()
        // Upload the data
        val id = upload(data).await()
        val digest = drb.transmittedDigest
        // Return
        return Pair(key, UploadResult(id, "${server}/file/$id", digest))
    }

    private fun handleSuccess(dispatcherName: String, attachment: SignalServiceAttachmentStream, attachmentKey: ByteArray, uploadResult: UploadResult) {
        Log.d(TAG, "Attachment uploaded successfully.")
        delegate?.handleJobSucceeded(this, dispatcherName)
        messageDataProvider.handleSuccessfulAttachmentUpload(attachmentID, attachment, attachmentKey, uploadResult)

        // We don't need to calculate the duration for voice notes, as they will have it set already.
        if (attachment.contentType.startsWith("audio/") && !attachment.voiceNote) {
            try {
                val inputStream = messageDataProvider.getAttachmentStream(attachmentID)!!.inputStream!!
                InputStreamMediaDataSource(inputStream).use { mediaDataSource ->
                    val durationMS = (DecodedAudio.create(mediaDataSource).totalDurationMicroseconds / 1000.0).toLong()
                    Log.d(TAG, "Audio attachment duration calculated as: $durationMS ms")
                    messageDataProvider.getDatabaseAttachment(attachmentID)?.attachmentId?.let { attachmentId ->
                        messageDataProvider.updateAudioAttachmentDuration(attachmentId, durationMS, threadID.toLong())
                    }
                }
            } catch (e: Exception) {
                Log.e("Loki", "Couldn't process audio attachment", e)
            }
        }

        storage.getMessageSendJob(messageSendJobID)?.let {
            val destination = it.destination as? Destination.OpenGroup ?: return@let
            val updatedJob = messageSendJobFactory.create(
                message = it.message,
                destination = Destination.OpenGroup(
                    destination.roomToken,
                    destination.server,
                    destination.whisperTo,
                    destination.whisperMods,
                    destination.fileIds + uploadResult.id
                ),
                statusCallback = it.statusCallback
            )
            updatedJob.id = it.id
            updatedJob.delegate = it.delegate
            updatedJob.failureCount = it.failureCount
            storage.persistJob(updatedJob)
        }
        storage.resumeMessageSendJobIfNeeded(messageSendJobID)
    }

    private fun handlePermanentFailure(dispatcherName: String, e: Exception) {
        Log.w(TAG, "Attachment upload failed permanently due to error: $this.")
        delegate?.handleJobFailedPermanently(this, dispatcherName, e)
        messageDataProvider.handleFailedAttachmentUpload(attachmentID)
        failAssociatedMessageSendJob(e)
    }

    private fun handleFailure(dispatcherName: String, e: Exception) {
        Log.w(TAG, "Attachment upload failed due to error: $this.")
        delegate?.handleJobFailed(this, dispatcherName, e)
        if (failureCount + 1 >= maxFailureCount) {
            failAssociatedMessageSendJob(e)
        }
    }

    private fun failAssociatedMessageSendJob(e: Exception) {
        val messageSendJob = storage.getMessageSendJob(messageSendJobID)
        MessageSender.handleFailedMessageSend(this.message, e)
        if (messageSendJob != null) {
            storage.markJobAsFailedPermanently(messageSendJobID)
        }
    }

    override fun serialize(): Data {
        val kryo = Kryo()
        kryo.isRegistrationRequired = false
        val serializedMessage = ByteArray(4096)
        val output = Output(serializedMessage, Job.MAX_BUFFER_SIZE_BYTES)
        kryo.writeClassAndObject(output, message)
        output.close()
        return Data.Builder()
            .putLong(ATTACHMENT_ID_KEY, attachmentID)
            .putString(THREAD_ID_KEY, threadID)
            .putByteArray(MESSAGE_KEY, output.toBytes())
            .putString(MESSAGE_SEND_JOB_ID_KEY, messageSendJobID)
            .build()
    }

    override fun getFactoryKey(): String {
        return KEY
    }

    class DeserializeFactory(private val factory: Factory): Job.DeserializeFactory<AttachmentUploadJob> {

        override fun create(data: Data): AttachmentUploadJob? {
            val serializedMessage = data.getByteArray(MESSAGE_KEY)
            val kryo = Kryo()
            kryo.isRegistrationRequired = false
            val input = Input(serializedMessage)
            val message: Message
            try {
                message = kryo.readClassAndObject(input) as Message
            } catch (e: Exception) {
                Log.e("Loki","Couldn't serialize the AttachmentUploadJob", e)
                return null
            }
            input.close()
            return factory.create(
                attachmentID = data.getLong(ATTACHMENT_ID_KEY),
                threadID = data.getString(THREAD_ID_KEY)!!,
                message = message,
                messageSendJobID = data.getString(MESSAGE_SEND_JOB_ID_KEY)!!
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            attachmentID: Long,
            @Assisted("threadID") threadID: String,
            message: Message,
            messageSendJobID: String
        ): AttachmentUploadJob
    }
}