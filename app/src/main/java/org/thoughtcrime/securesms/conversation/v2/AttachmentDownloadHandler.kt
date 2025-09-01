package org.thoughtcrime.securesms.conversation.v2

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.jobs.AttachmentDownloadJob
import org.session.libsession.messaging.jobs.AttachmentUploadJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentState
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.util.flatten
import org.thoughtcrime.securesms.util.timedBuffer

/**
 * [AttachmentDownloadHandler] is responsible for handling attachment download requests. These
 * requests will go through different level of checking before they are queued for download.
 *
 * To use this handler, call [downloadPendingAttachment] with the attachment that needs to be
 * downloaded. The call to [downloadPendingAttachment] is cheap and can be called multiple times.
 */
class AttachmentDownloadHandler @AssistedInject constructor(
    private val storage: StorageProtocol,
    private val messageDataProvider: MessageDataProvider,
    private val recipientRepository: RecipientRepository,
    @Assisted private val scope: CoroutineScope,
    private val downloadJobFactory: AttachmentDownloadJob.Factory
) {
    companion object {
        private const val BUFFER_TIMEOUT_MILLS = 500L
        private const val BUFFER_MAX_ITEMS = 10
        private const val LOG_TAG = "AttachmentDownloadHelper"
    }

    private val downloadRequests = Channel<DatabaseAttachment>(UNLIMITED)
    private val jobQueue: JobQueue = JobQueue.shared

    init {
        scope.launch(Dispatchers.Default) {
            downloadRequests
                .receiveAsFlow()
                .timedBuffer(BUFFER_TIMEOUT_MILLS, BUFFER_MAX_ITEMS)
                .map(::filterEligibleAttachments)
                .flatten()
                .collect { attachment ->
                    jobQueue.add(
                        downloadJobFactory.create(
                            attachmentID = attachment.attachmentId.rowId,
                            mmsMessageId = attachment.mmsId
                        )
                    )
                }
        }
    }

    /**
     * Filter attachments that are eligible for creating download jobs.
     *
     */
    private fun filterEligibleAttachments(attachments: List<DatabaseAttachment>): List<DatabaseAttachment> {
        val pendingAttachmentIDs = storage
            .getAllPendingJobs(AttachmentDownloadJob.KEY, AttachmentUploadJob.KEY)
            .values
            .mapNotNull {
                (it as? AttachmentUploadJob)?.attachmentID
                    ?: (it as? AttachmentDownloadJob)?.attachmentID
            }
            .toSet()


        return attachments.filter { attachment ->
            eligibleForDownloadTask(
                attachment,
                pendingAttachmentIDs,
            )
        }
    }

    /**
     * Check if the attachment is eligible for download task.
     */
    private fun eligibleForDownloadTask(
        attachment: DatabaseAttachment,
        pendingJobsAttachmentRowIDs: Set<Long>,
    ): Boolean {
        if (attachment.attachmentId.rowId in pendingJobsAttachmentRowIDs) {
            return false
        }

        val threadID = storage.getThreadIdForMms(attachment.mmsId)

        return AttachmentDownloadJob.eligibleForDownload(
            threadID = threadID,
            storage = storage,
            recipientRepository = recipientRepository,
            messageDataProvider = messageDataProvider,
            mmsId = attachment.mmsId,
        )
    }


    fun downloadPendingAttachment(attachment: DatabaseAttachment) {
        if (attachment.transferState != AttachmentState.PENDING.value) {
            Log.i(
                LOG_TAG,
                "Attachment ${attachment.attachmentId} is not pending nor failed, skipping download (state = ${attachment.transferState})}"
            )
            return
        }

        downloadRequests.trySend(attachment)
    }

    fun retryFailedAttachments(attachments: List<DatabaseAttachment>){
        attachments.forEach { attachment ->
            if (attachment.transferState != AttachmentState.FAILED.value){
                Log.d(
                    LOG_TAG,
                    "Attachment ${attachment.attachmentId} is not failed, skipping retry"
                )

                return@forEach
            }

            downloadRequests.trySend(attachment)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)): AttachmentDownloadHandler
    }
}
