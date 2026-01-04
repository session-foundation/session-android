package org.thoughtcrime.securesms.audio.model


import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.mms.AudioSlide

object PlayableAudioMapper {

    /**
     * Create a PlayableAudio from the existing Slide/Attachment model.
     *
     * @param messageId stable message identifier (mmsId or your MessageId long)
     * @param senderName for notification / UI (optional)
     * @param titleOverride optional (e.g., "Voice message" vs filename)
     */
    fun fromAudioSlide(
        slide: AudioSlide,
        messageId: MessageId,
        thread: Address.Conversable,
        senderName: String? = null,
        titleOverride: String? = null
    ): PlayableAudio? {
        val attachment: Attachment = slide.asAttachment()
        val uri = attachment.dataUri ?: return null

        val attachmentId: String? = (attachment as? DatabaseAttachment)
            ?.attachmentId
            ?.toString()

        val isVoice = attachment.isVoiceNote
        val durationHint = attachment.audioDurationMs

        val title = titleOverride
            ?: slide.filename

        val artist = senderName //todo AUDIO can we get the artist is not a voice note?

        return PlayableAudio(
            messageId = messageId,
            uri = uri,
            thread = thread,
            isVoiceNote = isVoice,
            durationMs = durationHint,
            title = title,
            artist = artist
        )
    }
}
