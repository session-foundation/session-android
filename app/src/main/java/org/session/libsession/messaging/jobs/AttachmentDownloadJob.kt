package org.session.libsession.messaging.jobs

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentState
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.DecodedAudio
import org.session.libsession.utilities.DownloadUtilities
import org.session.libsession.utilities.InputStreamMediaDataSource
import org.session.libsignal.streams.AttachmentCipherInputStream
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.HTTP
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.model.MessageId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

class AttachmentDownloadJob(val attachmentID: Long, val mmsMessageId: Long) : Job {
    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0

    // Error
    internal sealed class Error(val description: String) : Exception(description) {
        object NoAttachment : Error("No such attachment.")
        object NoThread: Error("Thread no longer exists")
        object NoSender: Error("Thread recipient or sender does not exist")
        object DuplicateData: Error("Attachment already downloaded")
    }

    // Settings
    override val maxFailureCount: Int = 2

    companion object {
        const val KEY: String = "AttachmentDownloadJob"

        // Keys used for database storage
        private val ATTACHMENT_ID_KEY = "attachment_id"
        private val TS_INCOMING_MESSAGE_ID_KEY = "tsIncoming_message_id"

        /**
         * Check if the attachment in the given message is eligible for download.
         *
         * Note that this function only checks for the eligibility of the attachment in the sense
         * of whether the download is allowed, it does not check if the download has already taken
         * place.
         */
        fun eligibleForDownload(threadID: Long,
                                storage: StorageProtocol,
                                messageDataProvider: MessageDataProvider,
                                mmsId: Long): Boolean {
            val threadRecipient = storage.getRecipientForThread(threadID) ?: return false

            // if we are the sender we are always eligible
            val selfSend = messageDataProvider.isOutgoingMessage(MessageId(mmsId, true))
            if (selfSend) {
                return true
            }

            return storage.shouldAutoDownloadAttachments(threadRecipient)
        }
    }

    override suspend fun execute(dispatcherName: String) {
        val storage = MessagingModuleConfiguration.shared.storage
        val messageDataProvider = MessagingModuleConfiguration.shared.messageDataProvider
        val threadID = storage.getThreadIdForMms(mmsMessageId)

        val handleFailure: (java.lang.Exception, attachmentId: AttachmentId?) -> Unit = { exception, attachment ->
            if(exception is HTTP.HTTPRequestFailedException && exception.statusCode == 404){
                attachment?.let { id ->
                    Log.d("AttachmentDownloadJob", "Setting attachment state = failed, have attachment")
                    messageDataProvider.setAttachmentState(AttachmentState.EXPIRED, id, mmsMessageId)
                } ?: run {
                    Log.d("AttachmentDownloadJob", "Setting attachment state = failed, don't have attachment")
                    messageDataProvider.setAttachmentState(AttachmentState.EXPIRED, AttachmentId(attachmentID,0), mmsMessageId)
                }
            } else if (exception == Error.NoAttachment
                    || exception == Error.NoThread
                    || exception == Error.NoSender
                    || (exception is OnionRequestAPI.HTTPRequestFailedAtDestinationException && exception.statusCode == 400)) {
                attachment?.let { id ->
                    Log.d("AttachmentDownloadJob", "Setting attachment state = failed, have attachment")
                    messageDataProvider.setAttachmentState(AttachmentState.FAILED, id, mmsMessageId)
                } ?: run {
                    Log.d("AttachmentDownloadJob", "Setting attachment state = failed, don't have attachment")
                    messageDataProvider.setAttachmentState(AttachmentState.FAILED, AttachmentId(attachmentID,0), mmsMessageId)
                }
                this.handlePermanentFailure(dispatcherName, exception)
            } else if (exception == Error.DuplicateData) {
                attachment?.let { id ->
                    Log.d("AttachmentDownloadJob", "Setting attachment state = done from duplicate data")
                    messageDataProvider.setAttachmentState(AttachmentState.DONE, id, mmsMessageId)
                } ?: run {
                    Log.d("AttachmentDownloadJob", "Setting attachment state = done from duplicate data")
                    messageDataProvider.setAttachmentState(AttachmentState.DONE, AttachmentId(attachmentID,0), mmsMessageId)
                }
                this.handleSuccess(dispatcherName)
            } else {
                if (failureCount + 1 >= maxFailureCount) {
                    attachment?.let { id ->
                        Log.d("AttachmentDownloadJob", "Setting attachment state = failed from max failure count, have attachment")
                        messageDataProvider.setAttachmentState(AttachmentState.FAILED, id, mmsMessageId)
                    } ?: run {
                        Log.d("AttachmentDownloadJob", "Setting attachment state = failed from max failure count, don't have attachment")
                        messageDataProvider.setAttachmentState(AttachmentState.FAILED, AttachmentId(attachmentID,0), mmsMessageId)
                    }
                }
                this.handleFailure(dispatcherName, exception)
            }
        }

        if (threadID < 0) {
            handleFailure(Error.NoThread, null)
            return
        }

        if (!eligibleForDownload(threadID, storage, messageDataProvider, mmsMessageId)) {
            handleFailure(Error.NoSender, null)
            return
        }

        var tempFile: File? = null
        var attachment: DatabaseAttachment? = null

        try {
            attachment = messageDataProvider.getDatabaseAttachment(attachmentID)
                ?: return handleFailure(Error.NoAttachment, null)
            if (attachment.hasData()) {
                handleFailure(Error.DuplicateData, attachment.attachmentId)
                return
            }
            messageDataProvider.setAttachmentState(AttachmentState.DOWNLOADING, attachment.attachmentId, this.mmsMessageId)
            val openGroup = storage.getOpenGroup(threadID)
            val downloadedData = if (openGroup == null) {
                Log.d("AttachmentDownloadJob", "downloading normal attachment")
                DownloadUtilities.downloadFromFileServer(attachment.url)
            } else {
                Log.d("AttachmentDownloadJob", "downloading open group attachment")
                val url = attachment.url.toHttpUrlOrNull()!!
                val fileID = url.pathSegments.last()
                OpenGroupApi.download(fileID, openGroup.room, openGroup.server).await()
            }

            tempFile = createTempFile().also { file ->
                FileOutputStream(file).use {
                    it.write(downloadedData.data, downloadedData.offset, downloadedData.len)
                }
            }

            Log.d("AttachmentDownloadJob", "getting input stream")
            val inputStream = getInputStream(tempFile, attachment)

            Log.d("AttachmentDownloadJob", "inserting attachment")
            messageDataProvider.insertAttachment(mmsMessageId, attachment.attachmentId, inputStream)
            if (attachment.contentType.startsWith("audio/")) {
                // process the duration
                    try {
                        InputStreamMediaDataSource(getInputStream(tempFile, attachment)).use { mediaDataSource ->
                            val durationMs = (DecodedAudio.create(mediaDataSource).totalDurationMicroseconds / 1000.0).toLong()
                            messageDataProvider.updateAudioAttachmentDuration(
                                attachment.attachmentId,
                                durationMs,
                                threadID
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("Loki", "Couldn't process audio attachment", e)
                    }
            }
            Log.d("AttachmentDownloadJob", "deleting tempfile")
            tempFile.delete()
            Log.d("AttachmentDownloadJob", "succeeding job")
            handleSuccess(dispatcherName)
        } catch (e: Exception) {
            Log.e("AttachmentDownloadJob", "Error processing attachment download", e)
            tempFile?.delete()
            return handleFailure(e,attachment?.attachmentId)
        }
    }

    private fun getInputStream(tempFile: File, attachment: DatabaseAttachment): InputStream {
        // Assume we're retrieving an attachment for an open group server if the digest is not set
        return if (attachment.digest?.size ?: 0 == 0 || attachment.key.isNullOrEmpty()) {
            Log.d("AttachmentDownloadJob", "getting input stream with no attachment digest")
            FileInputStream(tempFile)
        } else {
            Log.d("AttachmentDownloadJob", "getting input stream with attachment digest")
            AttachmentCipherInputStream.createForAttachment(tempFile, attachment.size, Base64.decode(attachment.key), attachment.digest)
        }
    }

    private fun handleSuccess(dispatcherName: String) {
        Log.w("AttachmentDownloadJob", "Attachment downloaded successfully.")
        delegate?.handleJobSucceeded(this, dispatcherName)
    }

    private fun handlePermanentFailure(dispatcherName: String, e: Exception) {
        delegate?.handleJobFailedPermanently(this, dispatcherName, e)
    }

    private fun handleFailure(dispatcherName: String, e: Exception) {
        delegate?.handleJobFailed(this, dispatcherName, e)
    }

    private fun createTempFile(): File {
        val file = File.createTempFile("push-attachment", "tmp", MessagingModuleConfiguration.shared.context.cacheDir)
        file.deleteOnExit()
        return file
    }

    override fun serialize(): Data {
        return Data.Builder()
            .putLong(ATTACHMENT_ID_KEY, attachmentID)
            .putLong(TS_INCOMING_MESSAGE_ID_KEY, mmsMessageId)
            .build();
    }

    override fun getFactoryKey(): String {
        return KEY
    }

    class Factory : Job.Factory<AttachmentDownloadJob> {

        override fun create(data: Data): AttachmentDownloadJob {
            return AttachmentDownloadJob(data.getLong(ATTACHMENT_ID_KEY), data.getLong(TS_INCOMING_MESSAGE_ID_KEY))
        }
    }
}