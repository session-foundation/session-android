package org.thoughtcrime.securesms.conversation.v2.messages

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewPendingAttachmentBinding
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.conversation.v2.dialogs.DownloadDialog
import org.thoughtcrime.securesms.util.ActivityDispatcher
import java.util.Locale

class PendingAttachmentView: LinearLayout {
    private val binding by lazy { ViewPendingAttachmentBinding.bind(this) }
    enum class AttachmentType {
        AUDIO,
        DOCUMENT,
        MEDIA
    }

    private var attachmentId: AttachmentId? = null

    // region Lifecycle
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    // endregion

    // region Updating
    fun bind(attachmentType: AttachmentType, @ColorInt textColor: Int, attachmentId: AttachmentId) {
        val (iconRes, stringRes) = when (attachmentType) {
            AttachmentType.AUDIO -> R.drawable.ic_microphone to R.string.Slide_audio
            AttachmentType.DOCUMENT -> R.drawable.ic_document_large_light to R.string.document
            AttachmentType.MEDIA -> R.drawable.ic_image_white_24dp to R.string.media
        }
        val iconDrawable = ContextCompat.getDrawable(context,iconRes)!!
        iconDrawable.mutate().setTint(textColor)
        val text = context.getString(R.string.UntrustedAttachmentView_download_attachment, context.getString(stringRes).toLowerCase(Locale.ROOT))

        binding.untrustedAttachmentIcon.setImageDrawable(iconDrawable)
        binding.untrustedAttachmentTitle.text = text
        this.attachmentId = attachmentId
    }
    // endregion

    // region Interaction
    fun showDownloadDialog(threadRecipient: Recipient) {
        attachmentId?.let { attachmentId ->
            ActivityDispatcher.get(context)?.showDialog(DownloadDialog(threadRecipient, attachmentId))
        }
    }

}