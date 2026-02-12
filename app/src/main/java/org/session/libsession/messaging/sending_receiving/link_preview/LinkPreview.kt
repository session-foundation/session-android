package org.session.libsession.messaging.sending_receiving.link_preview

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment

@Serializable
data class LinkPreview (
    val url: String,
    val title: String,
    val attachmentId: AttachmentId? = null,
    @Transient
    val thumbnail: Attachment? = null,
) {
    /**
     * Constructor when we already have a DatabaseAttachment thumbnail.
     */
    constructor(
        url: String,
        title: String,
        thumbnail: DatabaseAttachment
    ) : this(
        url = url,
        title = title,
        attachmentId = thumbnail.attachmentId,
        thumbnail = thumbnail,
    )

    /**
     * Constructor when we already have an Attachment (nullable).
     */
    constructor(
        url: String,
        title: String,
        thumbnail: Attachment?
    ) : this(
        url = url,
        title = title,
        attachmentId = null,
        thumbnail = thumbnail,
    )

}
