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
import network.loki.messenger.R
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentState
import org.session.libsession.messaging.sending_receiving.attachments.UriAttachment
import org.session.libsession.utilities.MediaTypes
import org.thoughtcrime.securesms.util.FilenameUtils

class AudioSlide : Slide {

    override val contentDescription: String
        get() = context.getString(R.string.audio)

    override val thumbnailUri: Uri?
        get() = null

    constructor(context: Context, uri: Uri, filename: String?, dataSize: Long, voiceNote: Boolean, durationMills: Long)
        // Note: The `caption` field of `constructAttachmentFromUri` is repurposed to store the interim
        : super(context,
                constructAttachmentFromUri(
                    context,
                    uri,
                    MediaTypes.AUDIO_UNSPECIFIED,
                    dataSize,
                    0,         // width
                    0,         // height
                    false,     // hasThumbnail
                    filename,
                    null,
                    voiceNote,
                    false,
                    durationMills)     // quote
                )

    constructor(context: Context, uri: Uri, filename: String?, dataSize: Long, contentType: String, voiceNote: Boolean, durationMills: Long)
        : super(context,
                UriAttachment(
                    uri,
                    null,        // thumbnailUri
                    contentType,
                    AttachmentState.DOWNLOADING.value,
                    dataSize,
                    0,           // width
                    0,           // height
                    filename,
                    null,        // fastPreflightId
                    voiceNote,
                    false,       // quote
                    null,
                    durationMills)
                )

    constructor(context: Context, attachment: Attachment) : super(context, attachment)

    override fun hasPlaceholder() = true
    override fun hasImage()       = false
    override fun hasAudio()       = true

    // Legacy voice messages don't have filenames at all - so should we come across one we must synthesize a filename using the delivery date obtained from the attachment
    override fun generateSuitableFilenameFromUri(context: Context, uri: Uri?): String {
        return FilenameUtils.constructAudioMessageFilenameFromAttachment(context, attachment)
    }

    @DrawableRes
    override fun getPlaceholderRes(theme: Resources.Theme?) = R.drawable.ic_volume_2
}
