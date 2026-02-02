package org.session.libsession.messaging.jobs

import javax.inject.Inject

class SessionJobManagerFactories @Inject constructor(
    private val attachmentDownloadJobFactory: AttachmentDownloadJob.Factory,
    private val attachmentUploadJobFactory: AttachmentUploadJob.Factory,
    private val trimThreadFactory: TrimThreadJob.Factory,
    private val messageSendJobFactory: MessageSendJob.Factory,
    private val deleteJobFactory: OpenGroupDeleteJob.Factory,
    private val inviteContactsJobFactory: InviteContactsJob.Factory,
) {

    fun getSessionJobFactories(): Map<String, Job.DeserializeFactory<out Job>> {
        return mapOf(
            AttachmentDownloadJob.KEY to attachmentDownloadJobFactory,
            AttachmentUploadJob.KEY to attachmentUploadJobFactory,
            MessageSendJob.KEY to messageSendJobFactory,
            TrimThreadJob.KEY to trimThreadFactory,
            OpenGroupDeleteJob.KEY to deleteJobFactory,
            InviteContactsJob.KEY to inviteContactsJobFactory,
        )
    }
}