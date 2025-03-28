package org.thoughtcrime.securesms.util

import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.AttachmentDownloadJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentState
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment


fun JobQueue.createAndStartAttachmentDownload(attachment: DatabaseAttachment) {
    val attachmentId = attachment.attachmentId.rowId
    if (attachment.transferState == AttachmentState.PENDING.value
        && MessagingModuleConfiguration.shared.storage.getAttachmentUploadJob(attachmentId) == null) {
        // start download
        add(AttachmentDownloadJob(attachmentId, attachment.mmsId))
    }
}