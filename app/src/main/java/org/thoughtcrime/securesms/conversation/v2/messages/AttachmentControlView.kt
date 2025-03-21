package org.thoughtcrime.securesms.conversation.v2.messages

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewAttachmentControlBinding
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentState
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.utilities.StringSubstitutionConstants.FILE_TYPE_KEY
import org.session.libsession.utilities.Util
import org.session.libsession.utilities.getColorFromAttr
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.conversation.v2.dialogs.AutoDownloadDialog
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.ui.findActivity
import org.thoughtcrime.securesms.util.ActivityDispatcher
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class AttachmentControlView: LinearLayout {
    private val binding by lazy { ViewAttachmentControlBinding.bind(this) }
    enum class AttachmentType {
        VOICE,
        AUDIO,
        DOCUMENT,
        IMAGE,
        VIDEO,
    }

    // region Lifecycle
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    // endregion
    @Inject lateinit var storage: StorageProtocol

    val errorColor by lazy {
        context.getColorFromAttr(R.attr.danger)
    }

    // region Updating
    private fun getAttachmentData(attachmentType: AttachmentType, messageTotalAttachment: Int): Pair<Int, Int> {
        return when (attachmentType) {
            AttachmentType.VOICE -> Pair(R.string.messageVoice, R.drawable.ic_mic)
            AttachmentType.AUDIO -> Pair(R.string.audio, R.drawable.ic_volume_2)
            AttachmentType.DOCUMENT -> Pair(R.string.document, R.drawable.ic_file)
            AttachmentType.IMAGE -> {
                if(messageTotalAttachment > 1) Pair(R.string.images, R.drawable.ic_images)
                else Pair(R.string.image, R.drawable.ic_image)
            }
            AttachmentType.VIDEO -> Pair(R.string.video, R.drawable.ic_square_play)
        }
    }

    fun bind(
        attachmentType: AttachmentType,
        @ColorInt textColor: Int,
        state: AttachmentState,
        allMessageAttachments: List<Slide>,
    ) {
        val (stringRes, iconRes) = getAttachmentData(attachmentType, allMessageAttachments.size)

        val totalSize = Util.getPrettyFileSize(allMessageAttachments.sumOf { it.fileSize })

        binding.pendingDownloadIcon.setImageResource(iconRes)

        when(state){
            AttachmentState.EXPIRED -> {
                val expiredColor = textColor.also { alpha = 0.7f }

                binding.pendingDownloadIcon.setColorFilter(expiredColor)
                binding.pendingDownloadSize.isVisible = false

                binding.pendingDownloadTitle.apply {
                    text = context.getString(R.string.attachmentsExpired)
                    setTextColor(expiredColor)
                    setTypeface(typeface, android.graphics.Typeface.ITALIC)
                }

                binding.separator.isVisible = false
                binding.pendingDownloadSubtitle.isVisible = false
            }

            AttachmentState.DOWNLOADING -> {
                binding.pendingDownloadIcon.setColorFilter(textColor)

                //todo: ATTACHMENT This will need to be tweaked to dynamically show the the downloaded amount
                binding.pendingDownloadSize.apply {
                    text = totalSize
                    setTextColor(textColor)
                    isVisible = true
                }

                binding.pendingDownloadTitle.apply{
                    text = context.getString(R.string.downloading)
                    setTextColor(textColor)
                    setTypeface(typeface, android.graphics.Typeface.NORMAL)
                }

                binding.separator.apply {
                    imageTintList = ColorStateList.valueOf(textColor)
                    isVisible = true
                }

                binding.pendingDownloadSubtitle.isVisible = false
            }

            AttachmentState.FAILED -> {
                binding.pendingDownloadIcon.setColorFilter(textColor)

                binding.pendingDownloadSize.apply {
                    text = totalSize
                    setTextColor(errorColor)
                    isVisible = true
                }

                binding.pendingDownloadTitle.apply{
                    text = context.getString(R.string.failedToDownload)
                    setTextColor(errorColor)
                    setTypeface(typeface, android.graphics.Typeface.NORMAL)
                }

                binding.separator.apply {
                    imageTintList = ColorStateList.valueOf(errorColor)
                    isVisible = true
                }

                binding.pendingDownloadSubtitle.isVisible = true
            }

            else -> {
                binding.pendingDownloadIcon.setColorFilter(textColor)

                binding.pendingDownloadSize.apply {
                    text = totalSize
                    setTextColor(textColor)
                    isVisible = true
                }

                binding.pendingDownloadTitle.apply{
                    text = Phrase.from(context, R.string.attachmentsTapToDownload)
                        .put(FILE_TYPE_KEY, context.getString(stringRes).lowercase(Locale.ROOT))
                        .format()
                    setTextColor(textColor)
                    setTypeface(typeface, android.graphics.Typeface.NORMAL)
                }

                binding.separator.apply {
                    imageTintList = ColorStateList.valueOf(textColor)
                    isVisible = true
                }

                binding.pendingDownloadSubtitle.isVisible = false
            }
        }
    }
    // endregion

    // region Interaction
    fun showDownloadDialog(threadRecipient: Recipient, attachment: DatabaseAttachment) {
        if (!storage.shouldAutoDownloadAttachments(threadRecipient)) {
            // just download
            (context.findActivity() as? ActivityDispatcher)?.showDialog(AutoDownloadDialog(threadRecipient, attachment))
        }
    }
}