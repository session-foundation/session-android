package org.thoughtcrime.securesms.conversation.v2

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.text.Typography.ellipsis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.jobs.AttachmentDownloadJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentTransferProgress
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.utilities.Util
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.MediaPreviewArgs
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.TitledText

@HiltViewModel
class MessageDetailsViewModel @Inject constructor(
    private val attachmentDb: AttachmentDatabase,
    private val lokiMessageDatabase: LokiMessageDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val threadDb: ThreadDatabase,
    private val repository: ConversationRepository,
    private val deprecationManager: LegacyGroupDeprecationManager,
    private val context: ApplicationContext
) : ViewModel() {

    private var job: Job? = null

    private val state = MutableStateFlow(MessageDetailsState())
    val stateFlow = state.asStateFlow()

    private val event = Channel<Event>()
    val eventFlow = event.receiveAsFlow()

    var timestamp: Long = 0L
        set(value) {
            job?.cancel()

            field = value
            val messageRecord = mmsSmsDatabase.getMessageForTimestamp(timestamp)

            if (messageRecord == null) {
                viewModelScope.launch { event.send(Event.Finish) }
                return
            }

            val mmsRecord = messageRecord as? MmsMessageRecord

            job = viewModelScope.launch {
                repository.changes(messageRecord.threadId)
                    .filter { mmsSmsDatabase.getMessageForTimestamp(value) == null }
                    .collect { event.send(Event.Finish) }
            }

            state.value = messageRecord.run {
                val slides = mmsRecord?.slideDeck?.slides ?: emptyList()

                val recipient = threadDb.getRecipientForThreadId(threadId)!!
                val isDeprecatedLegacyGroup = recipient.isLegacyGroupRecipient &&
                                              deprecationManager.isDeprecated


                val errorString = lokiMessageDatabase.getErrorMessage(id)

                MessageDetailsState(
                    attachments = slides.map(::Attachment),
                    record = messageRecord,

                    // Set the "Sent" message info TitledText appropriately
                    sent = if (messageRecord.isSending && errorString == null) {
                        val sendingWithEllipsisString = context.getString(R.string.sending) + ellipsis // e.g., "Sending…"
                        TitledText(sendingWithEllipsisString, null)
                    } else if (messageRecord.isSent && errorString == null) {
                        dateReceived.let(::Date).toString().let { TitledText(R.string.sent, it) }
                    } else {
                        null // Not sending or sent? Don't display anything for the "Sent" element.
                    },

                    // Set the "Received" message info TitledText appropriately
                    received = if (messageRecord.isIncoming && errorString == null) {
                        dateReceived.let(::Date).toString().let { TitledText(R.string.received, it) }
                    } else {
                        null // Not incoming? Then don't display anything for the "Received" element.
                    },

                    error = errorString?.let { TitledText(context.getString(R.string.theError) + ":", it) },
                    senderInfo = individualRecipient.run { TitledText(name, address.toString()) },
                    sender = individualRecipient,
                    thread = recipient,
                    readOnly = isDeprecatedLegacyGroup
                )
            }
        }

    private val Slide.details: List<TitledText>
        get() = listOfNotNull(
            TitledText(R.string.attachmentsFileId, filename),
            TitledText(R.string.attachmentsFileType, asAttachment().contentType),
            TitledText(R.string.attachmentsFileSize, Util.getPrettyFileSize(fileSize)),
            takeIf { it is ImageSlide }
                ?.let(Slide::asAttachment)
                ?.run { "${width}x$height" }
                ?.let { TitledText(R.string.attachmentsResolution, it) },
            attachmentDb.duration(this)?.let { TitledText(R.string.attachmentsDuration, it) },
        )

    private fun AttachmentDatabase.duration(slide: Slide): String? =
        slide.takeIf { it.hasAudio() }
            ?.run { asAttachment() as? DatabaseAttachment }
            ?.run { getAttachmentAudioExtras(attachmentId)?.durationMs }
            ?.takeIf { it > 0 }
            ?.let {
                String.format(
                    "%01d:%02d",
                    TimeUnit.MILLISECONDS.toMinutes(it),
                    TimeUnit.MILLISECONDS.toSeconds(it) % 60
                )
            }

    fun Attachment(slide: Slide): Attachment = Attachment(slide.details, slide.filename, slide.uri, hasImage = (slide is ImageSlide))

    fun onClickImage(index: Int) {
        val state = state.value
        val mmsRecord = state.mmsRecord ?: return
        val slide = mmsRecord.slideDeck.slides[index] ?: return
        // only open to downloaded images
        if (slide.transferState == AttachmentTransferProgress.TRANSFER_PROGRESS_FAILED) {
            // Restart download here (on IO thread)
            (slide.asAttachment() as? DatabaseAttachment)?.let { attachment ->
                onAttachmentNeedsDownload(attachment)
            }
        }

        if (slide.isInProgress) return

        viewModelScope.launch {
            MediaPreviewArgs(slide, state.mmsRecord, state.thread)
                .let(Event::StartMediaPreview)
                .let { event.send(it) }
        }
    }

    fun onAttachmentNeedsDownload(attachment: DatabaseAttachment) {
        viewModelScope.launch(Dispatchers.IO) {
            JobQueue.shared.add(AttachmentDownloadJob(attachment.attachmentId.rowId, attachment.mmsId))
        }
    }
}

data class MessageDetailsState(
    val attachments: List<Attachment> = emptyList(),
    val imageAttachments: List<Attachment> = attachments.filter { it.hasImage },
    val nonImageAttachmentFileDetails: List<TitledText>? = attachments.firstOrNull { !it.hasImage }?.fileDetails,
    val record: MessageRecord? = null,
    val mmsRecord: MmsMessageRecord? = record as? MmsMessageRecord,
    val sent: TitledText? = null,
    val received: TitledText? = null,
    val error: TitledText? = null,
    val senderInfo: TitledText? = null,
    val sender: Recipient? = null,
    val thread: Recipient? = null,
    val readOnly: Boolean = false,
) {
    val fromTitle = GetString(R.string.from)
    val canReply: Boolean get() = !readOnly && record?.isOpenGroupInvitation != true
    val canDelete: Boolean get() = !readOnly
}

data class Attachment(
    val fileDetails: List<TitledText>,
    val fileName: String?,
    val uri: Uri?,
    val hasImage: Boolean
)

sealed class Event {
    object Finish: Event()
    data class StartMediaPreview(val args: MediaPreviewArgs): Event()
}