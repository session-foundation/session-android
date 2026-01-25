package org.session.libsession.messaging.jobs

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import network.loki.messenger.libsession_util.encrypt.Attachments
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.messaging.file_server.FileUploadApi
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.open_groups.api.CommunityApiExecutor
import org.session.libsession.messaging.open_groups.api.CommunityApiRequest
import org.session.libsession.messaging.open_groups.api.CommunityFileUploadApi
import org.session.libsession.messaging.open_groups.api.execute
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.DecodedAudio
import org.session.libsession.utilities.InputStreamMediaDataSource
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UploadResult
import org.session.libsignal.messages.SignalServiceAttachmentStream
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.api.http.HttpBody
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.api.server.ServerApiRequest
import org.thoughtcrime.securesms.api.server.execute
import org.thoughtcrime.securesms.attachments.AttachmentProcessor
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
    private val attachmentProcessor: AttachmentProcessor,
    private val preferences: TextSecurePreferences,
    private val messageSender: MessageSender,
    private val serverApiExecutor: ServerApiExecutor,
    private val fileUploadApiFactory: FileUploadApi.Factory,
    private val communityApiExecutor: CommunityApiExecutor,
    private val communityFileUploadApiFactory: CommunityFileUploadApi.Factory,
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
                val keyAndResult = upload(
                    attachment = attachment,
                    encrypt = false
                ) { data, _ ->
                    val id = communityApiExecutor.execute(
                        CommunityApiRequest(
                            serverBaseUrl = threadAddress.serverUrl,
                            api = communityFileUploadApiFactory.create(
                                file = HttpBody.Bytes(data),
                                room = threadAddress.room,
                            )
                        )
                    )
                    id to "${threadAddress.serverUrl}/file/$id"
                }
                handleSuccess(dispatcherName, attachment, keyAndResult.first, keyAndResult.second)
            } else {
                val fileServer = preferences.alternativeFileServer ?: FileServerApi.DEFAULT_FILE_SERVER
                val keyAndResult = upload(
                    attachment = attachment,
                    encrypt = true
                ) { data, isDeterministicallyEncrypted ->
                    val result = serverApiExecutor.execute(
                        ServerApiRequest(
                            fileServer = fileServer,
                            api = fileUploadApiFactory.create(
                                data = data,
                                usedDeterministicEncryption = isDeterministicallyEncrypted,
                                fileServer = fileServer,
                                customExpiresDuration = null,
                            )
                        )
                    )

                    result.fileId to result.fileUrl
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

    private suspend fun upload(attachment: SignalServiceAttachmentStream,
                               encrypt: Boolean,
                               // Returning pair of fileId and the final file URL
                               upload: suspend (
                                   data: ByteArray,
                                   isDeterministicallyEncrypted: Boolean,
                               ) -> Pair<String, String>
    ): Pair<ByteArray, UploadResult> {
        val input = attachment.inputStream.use {
            it.readBytes()
        }

        val key: ByteArray
        val dataToUpload: ByteArray
        val digest: ByteArray?
        val deterministicallyEncrypted: Boolean

        when {
            encrypt && preferences.forcesDeterministicAttachmentEncryption -> {
                deterministicallyEncrypted = true
                val result = attachmentProcessor.encryptDeterministically(
                    plaintext = input,
                    domain = Attachments.Domain.Attachment
                )
                key = result.key
                dataToUpload = result.ciphertext
                digest = null
            }

            encrypt -> {
                deterministicallyEncrypted = false
                val result = attachmentProcessor.encryptAttachmentLegacy(plaintext = input)
                key = result.first.key
                dataToUpload = result.first.ciphertext
                digest = result.second
            }

            else -> {
                deterministicallyEncrypted = false
                key = byteArrayOf()
                dataToUpload = input
                digest = attachmentProcessor.digest(dataToUpload)
            }
        }

        val (id, url) = upload(dataToUpload, deterministicallyEncrypted)

        // Return
        return Pair(key, UploadResult(
            id = id,
            url = url,
            digest = digest,
        ))
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
        messageSender.handleFailedMessageSend(this.message, e)
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

    @AssistedFactory
    abstract class Factory : Job.DeserializeFactory<AttachmentUploadJob> {
        abstract fun create(
            attachmentID: Long,
            @Assisted("threadID") threadID: String,
            message: Message,
            messageSendJobID: String
        ): AttachmentUploadJob

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
            return create(
                attachmentID = data.getLong(ATTACHMENT_ID_KEY),
                threadID = data.getString(THREAD_ID_KEY)!!,
                message = message,
                messageSendJobID = data.getString(MESSAGE_SEND_JOB_ID_KEY)!!
            )
        }
    }
}