package org.session.libsession.messaging.sending_receiving.link_preview

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsignal.utilities.JsonUtil
import java.io.IOException
import java.util.Objects

class LinkPreview (

    @param:JsonProperty("url")
    @field:JsonProperty("url")
    val url: String,

    @param:JsonProperty("title")
    @field:JsonProperty("title")
    val title: String,

    @param:JsonProperty("attachmentId")
    @field:JsonProperty("attachmentId")
    val attachmentId: AttachmentId? = null
) {

    /**
     * Not serialized.
     * Only used in-memory when we already have the thumbnail.
     */
    @JsonIgnore
    var thumbnail: Attachment? = null
        private set

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
        attachmentId = thumbnail.attachmentId
    ) {
        this.thumbnail = thumbnail
    }

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
        attachmentId = null
    ) {
        this.thumbnail = thumbnail
    }

    @Throws(IOException::class)
    fun serialize(): String {
        return JsonUtil.toJsonThrows(this)
    }

    companion object {
        @Throws(IOException::class)
        fun deserialize(serialized: String): LinkPreview {
            return JsonUtil.fromJson(serialized, LinkPreview::class.java)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as LinkPreview
        return url == that.url && title == that.title && attachmentId == that.attachmentId && thumbnail == that.thumbnail
    }

    override fun hashCode(): Int {
        return Objects.hash(url, title, attachmentId, thumbnail)
    }
}
