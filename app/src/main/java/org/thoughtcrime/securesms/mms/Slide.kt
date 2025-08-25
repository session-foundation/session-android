/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http:></http:>//www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.mms

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import androidx.annotation.DrawableRes
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentState
import org.session.libsession.messaging.sending_receiving.attachments.UriAttachment
import org.session.libsession.utilities.StringSubstitutionConstants.EMOJI_KEY
import org.session.libsession.utilities.Util.equals
import org.session.libsession.utilities.Util.hashCode
import org.session.libsignal.utilities.Util.SECURE_RANDOM
import org.session.libsignal.utilities.guava.Optional
import org.thoughtcrime.securesms.util.FilenameUtils
import org.thoughtcrime.securesms.util.MediaUtil

abstract class Slide(@JvmField protected val context: Context, protected val attachment: Attachment) {
    val contentType: String
        get() = attachment.contentType

    val uri: Uri?
        get() = attachment.dataUri

    open val thumbnailUri: Uri?
        get() = attachment.thumbnailUri

    val body: Optional<String>
        get() {
            return if (MediaUtil.isAudio(attachment) && attachment.isVoiceNote) {
                 val voiceTxt = Phrase.from(context, R.string.messageVoiceSnippet)
                    .put(EMOJI_KEY, "🎙")
                    .format().toString()
                Optional.fromNullable(voiceTxt)
            } else {
                val txt = Phrase.from(context, R.string.attachmentsNotification)
                    .put(EMOJI_KEY, emojiForMimeType())
                    .format().toString()
                Optional.fromNullable(txt)
            }
        }

    private fun emojiForMimeType(): String =
        when {
            MediaUtil.isGif(attachment)   -> "🎡"
            MediaUtil.isImage(attachment) -> "📷"
            MediaUtil.isVideo(attachment) -> "🎥"
            MediaUtil.isAudio(attachment) -> "🎧"
            MediaUtil.isFile(attachment)  -> "📎"
            else -> "" // We don't provide emojis for other mime-types such as VCARD
        }

    val caption: Optional<String?>
        get() = Optional.fromNullable(attachment.caption)

    val filename: String by lazy {
        if (attachment.filename.isNullOrEmpty()) generateSuitableFilenameFromUri(context, attachment.dataUri) else attachment.filename
    }

    // Note: All slide types EXCEPT AudioSlide use this technique to synthesize a filename from a Uri - however AudioSlide has
    // its own custom version to handle legacy voice messages which lack filenames.
    open fun generateSuitableFilenameFromUri(context: Context, uri: Uri?): String {
        return FilenameUtils.getFilenameFromUri(context, attachment.dataUri, attachment.contentType)
    }

    val fastPreflightId: String?
        get() = attachment.fastPreflightId

    val fileSize: Long
        get() = attachment.size

    open fun hasImage(): Boolean { return false }

    open fun hasVideo(): Boolean { return false }

    open fun hasAudio(): Boolean { return false }

    open fun hasDocument(): Boolean { return false }

    open val contentDescription: String
        get() = ""

    fun asAttachment(): Attachment { return attachment }

    val isInProgress: Boolean
        get() = attachment.isInProgress

    val isPendingDownload: Boolean
        get() = transferState == AttachmentState.FAILED.value ||
                transferState == AttachmentState.PENDING.value

    val isDone: Boolean
        get() = transferState == AttachmentState.DONE.value

    val isFailed: Boolean
        get() = transferState == AttachmentState.FAILED.value

    val isExpired: Boolean
        get() = transferState == AttachmentState.EXPIRED.value

    private val transferState: Int
        get() = attachment.transferState

    @DrawableRes
    open fun getPlaceholderRes(theme: Resources.Theme?): Int {
        throw AssertionError("getPlaceholderRes() called for non-drawable slide")
    }

    open fun hasPlaceholder(): Boolean { return false }

    open fun hasPlayOverlay(): Boolean { return false }

    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (other !is Slide) return false

        return (equals(this.contentType, other.contentType) &&
                hasAudio()         == other.hasAudio()      &&
                hasImage()         == other.hasImage()      &&
                hasVideo()         == other.hasVideo())     &&
                this.transferState == other.transferState   &&
                equals(this.uri, other.uri)                 &&
                equals(this.thumbnailUri, other.thumbnailUri)
    }

    override fun hashCode(): Int {
        return hashCode(contentType, hasAudio(), hasImage(), hasVideo(), uri, thumbnailUri, transferState)
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        protected fun constructAttachmentFromUri(
            context: Context,
            uri: Uri,
            defaultMime: String,
            size: Long,
            width: Int,
            height: Int,
            hasThumbnail: Boolean,
            fileName: String?,
            caption: String?,
            voiceNote: Boolean,
            quote: Boolean,
            audioDurationMills: Long = -1L,
        ): Attachment {
            val resolvedType = Optional.fromNullable(MediaUtil.getMimeType(context, uri)).or(defaultMime)
            val fastPreflightId = SECURE_RANDOM.nextLong().toString()

            return UriAttachment(
                uri,
                if (hasThumbnail) uri else null,
                resolvedType!!,
                AttachmentState.DOWNLOADING.value,
                size,
                width,
                height,
                fileName,
                fastPreflightId,
                voiceNote,
                quote,
                caption,
                audioDurationMills
            )
        }
    }
}