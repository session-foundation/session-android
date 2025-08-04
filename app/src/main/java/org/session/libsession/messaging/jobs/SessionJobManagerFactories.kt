package org.session.libsession.messaging.jobs

import javax.inject.Inject

class SessionJobManagerFactories @Inject constructor(
    private val attachmentDownloadJobFactory: AttachmentDownloadJob.Factory,
    private val attachmentUploadJobFactory: AttachmentUploadJob.Factory,
    private val batchFactory: BatchMessageReceiveJob.Factory,
    private val messageSendJobFactory: MessageSendJob.Factory,
) {

    fun getSessionJobFactories(): Map<String, Job.DeserializeFactory<out Job>> {
        return mapOf(
            AttachmentDownloadJob.KEY to AttachmentDownloadJob.DeserializeFactory(attachmentDownloadJobFactory),
            AttachmentUploadJob.KEY to AttachmentUploadJob.DeserializeFactory(attachmentUploadJobFactory),
            MessageSendJob.KEY to MessageSendJob.DeserializeFactory(messageSendJobFactory),
            NotifyPNServerJob.KEY to NotifyPNServerJob.DeserializeFactory(),
            TrimThreadJob.KEY to TrimThreadJob.DeserializeFactory(),
            BatchMessageReceiveJob.KEY to BatchMessageReceiveJob.DeserializeFactory(batchFactory),
            GroupAvatarDownloadJob.KEY to GroupAvatarDownloadJob.DeserializeFactory(),
            BackgroundGroupAddJob.KEY to BackgroundGroupAddJob.DeserializeFactory(),
            OpenGroupDeleteJob.KEY to OpenGroupDeleteJob.DeserializeFactory(),
        )
    }
}