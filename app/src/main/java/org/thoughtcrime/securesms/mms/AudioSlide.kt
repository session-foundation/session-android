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
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentTransferProgress
import org.session.libsession.messaging.sending_receiving.attachments.UriAttachment
import org.session.libsession.utilities.MediaTypes

class AudioSlide : Slide {
    constructor(context: Context, uri: Uri, filename: String?, dataSize: Long, voiceNote: Boolean) : super(context, constructAttachmentFromUri(context, uri, MediaTypes.AUDIO_UNSPECIFIED, dataSize, 0, 0, false, filename, null, voiceNote, false))

    constructor(context: Context, uri: Uri, filename: String?, dataSize: Long, contentType: String, voiceNote: Boolean) : super(context, UriAttachment(uri, null, contentType, AttachmentTransferProgress.TRANSFER_PROGRESS_STARTED, dataSize, 0, 0, filename, null, voiceNote, false, null))

    constructor(context: Context, attachment: Attachment) : super(context, attachment)

    override fun hasPlaceholder() = true
    override fun hasImage() = true
    override fun hasAudio() = true
    //fun getContentDescription() = context.getString(R.string.audio)

    @DrawableRes
    override fun getPlaceholderRes(theme: Resources.Theme?) = R.drawable.ic_volume_2

}
