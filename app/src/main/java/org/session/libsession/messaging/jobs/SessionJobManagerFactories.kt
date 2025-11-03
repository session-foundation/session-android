package org.session.libsession.messaging.jobs

import javax.inject.Inject

class SessionJobManagerFactories @Inject constructor(
    private val attachmentDownloadJobFactory: AttachmentDownloadJob.Factory,
    private val attachmentUploadJobFactory: AttachmentUploadJob.Factory,
    private val batchFactory: BatchMessageReceiveJob.Factory,
    private val trimThreadFactory: TrimThreadJob.Factory,
    private val messageSendJobFactory: MessageSendJob.Factory,
    private val deleteJobFactory: OpenGroupDeleteJob.Factory
) {

    fun getSessionJobFactories(): Map<String, Job.DeserializeFactory<out Job>> {
        return mapOf(
            AttachmentDownloadJob.KEY to AttachmentDownloadJob.DeserializeFactory(attachmentDownloadJobFactory),
            AttachmentUploadJob.KEY to AttachmentUploadJob.DeserializeFactory(attachmentUploadJobFactory),
            MessageSendJob.KEY to MessageSendJob.DeserializeFactory(messageSendJobFactory),
            NotifyPNServerJob.KEY to NotifyPNServerJob.DeserializeFactory(),
            TrimThreadJob.KEY to trimThreadFactory,
            BatchMessageReceiveJob.KEY to batchFactory,
            OpenGroupDeleteJob.KEY to deleteJobFactory,
        )
    }
}