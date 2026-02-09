package org.thoughtcrime.securesms.audio.model

import android.net.Uri
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.RemoteFile
import org.thoughtcrime.securesms.database.model.MessageId

/**
 * Thin playback model derived from Slide/Attachment.
 * This is NOT a new domain model; it's just what the playback layer needs.
 */
data class PlayableAudio(
    val messageId: MessageId,
    val uri: Uri,
    val thread: Address.Conversable,
    val isVoiceNote: Boolean,
    val durationMs: Long, // from Attachment.audioDurationMs, may be -1
    val senderName: String?,
    val filename: String,
    val avatar: RemoteFile? = null // custom avatar of the sender, if any
)
