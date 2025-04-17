package org.thoughtcrime.securesms.conversation.v2

import android.net.Uri
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.Util
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.MediaPreviewArgs
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.DatabaseContentProviders
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.TitledText
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.observeChanges
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.text.Typography.ellipsis

@HiltViewModel
class MessageDetailsViewModel @Inject constructor(
    private val prefs: TextSecurePreferences,
    private val attachmentDb: AttachmentDatabase,
    private val lokiMessageDatabase: LokiMessageDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val threadDb: ThreadDatabase,
    private val deprecationManager: LegacyGroupDeprecationManager,
    private val context: ApplicationContext,
    private val messageDataProvider: MessageDataProvider,
    private val storage: Storage,
    private val avatarUtils: AvatarUtils
) : ViewModel() {

    private var job: Job? = null

    private val state = MutableStateFlow(MessageDetailsState())
    val stateFlow = state.asStateFlow()

    private val event = Channel<Event>()
    val eventFlow = event.receiveAsFlow()

    private val attachmentDownloadHandler = AttachmentDownloadHandler(
        storage = storage,
        messageDataProvider = messageDataProvider,
        scope = viewModelScope,
    )

    @OptIn(FlowPreview::class)
    var timestamp: Long = 0L
        set(value) {
            job?.cancel()

            field = value
            val messageRecord = mmsSmsDatabase.getMessageForTimestamp(timestamp)

            if (messageRecord == null) {
                viewModelScope.launch { event.send(Event.Finish) }
                return
            }

            // listen to conversation and attachments changes
            job = viewModelScope.launch {
                (context.contentResolver.observeChanges(DatabaseContentProviders.Conversation.getUriForThread(messageRecord.threadId)) as Flow<*>)
                    .debounce(200L)
                    .onStart { emit(Unit) }
                    .collect{
                        val updatedRecord = mmsSmsDatabase.getMessageForTimestamp(value)
                        if(updatedRecord == null) event.send(Event.Finish)
                        else {
                            createStateFromRecord(updatedRecord)
                        }
                    }
            }
        }

    private suspend fun createStateFromRecord(messageRecord: MessageRecord){
        val mmsRecord = messageRecord as? MmsMessageRecord

        withContext(Dispatchers.Default){
            state.value = messageRecord.run {
                val slides = mmsRecord?.slideDeck?.slides ?: emptyList()

                val conversation = threadDb.getRecipientForThreadId(threadId)!!
                val isDeprecatedLegacyGroup = conversation.isLegacyGroupRecipient &&
                        deprecationManager.isDeprecated


                val errorString = lokiMessageDatabase.getErrorMessage(id)

                var status: MessageStatus? = null
                // create a 'failed to send' status if appropriate
                if(messageRecord.isFailed){
                    status = MessageStatus(
                        title = context.getString(R.string.messageStatusFailedToSend),
                        icon = R.drawable.ic_triangle_alert,
                        errorStatus = true
                    )
                }

                val sender = if(messageRecord.isOutgoing){
                    Recipient.from(context, Address.fromSerialized(prefs.getLocalNumber() ?: ""), false)
                } else individualRecipient

                val attachments = slides.map(::Attachment)

                // we don't want to display image attachments in the carousel if their state isn't done
                val imageAttachments = attachments.filter { it.isDownloaded && it.hasImage }

                MessageDetailsState(
                    //todo: ATTACHMENT We should sort out the equals in DatabaseAttachment which is the reason the StateFlow think the objects are the same in spite of the transferState of an attachment being different. That way we could remove the timestamp below
                    timestamp = System.currentTimeMillis(), // used as a trick to force the state as  being marked aas different each time
                    attachments = attachments,
                    imageAttachments = imageAttachments,
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
                    status = status,
                    senderInfo = sender.run {
                        TitledText(
                            if(messageRecord.isOutgoing) context.getString(R.string.you) else name,
                            address.toString()
                        )
                    },
                    senderAvatarData = avatarUtils.getUIDataFromRecipient(sender),
                    thread = conversation,
                    readOnly = isDeprecatedLegacyGroup
                )
            }
        }
    }

    private val Slide.details: List<TitledText>
        get() = listOfNotNull(
            TitledText(R.string.attachmentsFileId, filename),
            TitledText(R.string.attachmentsFileType, asAttachment().contentType),
            TitledText(R.string.attachmentsFileSize, Formatter.formatFileSize(context, fileSize)),
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
                    Locale.getDefault(),
                    "%01d:%02d",
                    TimeUnit.MILLISECONDS.toMinutes(it),
                    TimeUnit.MILLISECONDS.toSeconds(it) % 60
                )
            }

    fun Attachment(slide: Slide): Attachment = Attachment(
        fileDetails = slide.details,
        fileName = slide.filename,
        uri = slide.thumbnailUri,
        hasImage = slide.hasImage(),
        isDownloaded = slide.isDone
    )

    fun onClickImage(index: Int) {
        val state = state.value
        val mmsRecord = state.mmsRecord ?: return
        val slide = mmsRecord.slideDeck.slides[index] ?: return
        // only open to downloaded images
        if (slide.isInProgress || slide.isFailed) return

        viewModelScope.launch {
            MediaPreviewArgs(slide, state.mmsRecord, state.thread)
                .let(Event::StartMediaPreview)
                .let { event.send(it) }
        }
    }

    fun retryFailedAttachments(attachments: List<DatabaseAttachment>){
        attachmentDownloadHandler.retryFailedAttachments(attachments)
    }
}

data class MessageDetailsState(
    val timestamp: Long = 0L,
    val attachments: List<Attachment> = emptyList(),
    val imageAttachments: List<Attachment> = emptyList(),
    val nonImageAttachmentFileDetails: List<TitledText>? = attachments.firstOrNull { !it.hasImage }?.fileDetails,
    val record: MessageRecord? = null,
    val mmsRecord: MmsMessageRecord? = record as? MmsMessageRecord,
    val sent: TitledText? = null,
    val received: TitledText? = null,
    val error: TitledText? = null,
    val status: MessageStatus? = null,
    val senderInfo: TitledText? = null,
    val senderAvatarData: AvatarUIData? = null,
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
    val hasImage: Boolean,
    val isDownloaded: Boolean
)

data class MessageStatus(
    val title: String,
    @DrawableRes val icon: Int,
    val errorStatus: Boolean
)

sealed class Event {
    object Finish: Event()
    data class StartMediaPreview(val args: MediaPreviewArgs): Event()
}