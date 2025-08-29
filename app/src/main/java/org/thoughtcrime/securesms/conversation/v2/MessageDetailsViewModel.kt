package org.thoughtcrime.securesms.conversation.v2

import android.net.Uri
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.isLegacyGroup
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientData
import org.session.libsession.utilities.recipients.displayName
import org.session.libsignal.utilities.IdPrefix
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.MediaPreviewArgs
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.pro.ProStatusManager.MessageProFeature.AnimatedAvatar
import org.thoughtcrime.securesms.pro.ProStatusManager.MessageProFeature.LongMessage
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.TitledText
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUtils
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.UserProfileModalCommands
import org.thoughtcrime.securesms.util.UserProfileModalData
import org.thoughtcrime.securesms.util.UserProfileUtils
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.text.Typography.ellipsis

@HiltViewModel(assistedFactory = MessageDetailsViewModel.Factory::class)
class MessageDetailsViewModel @AssistedInject constructor(
    @Assisted val messageId: MessageId,
    private val prefs: TextSecurePreferences,
    private val attachmentDb: AttachmentDatabase,
    private val lokiMessageDatabase: LokiMessageDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val threadDb: ThreadDatabase,
    private val deprecationManager: LegacyGroupDeprecationManager,
    private val context: ApplicationContext,
    private val avatarUtils: AvatarUtils,
    private val dateUtils: DateUtils,
    private val recipientRepository: RecipientRepository,
    private val proStatusManager: ProStatusManager,
    private val upmFactory: UserProfileUtils.UserProfileUtilsFactory,
    attachmentDownloadHandlerFactory: AttachmentDownloadHandler.Factory,
) : ViewModel() {
    private val state = MutableStateFlow(MessageDetailsState())
    val stateFlow = state.asStateFlow()

    private val event = Channel<Event>()
    val eventFlow = event.receiveAsFlow()

    private val _dialogState: MutableStateFlow<DialogsState> = MutableStateFlow(DialogsState())
    val dialogState: StateFlow<DialogsState> = _dialogState

    private val attachmentDownloadHandler = attachmentDownloadHandlerFactory.create(viewModelScope)

    private var userProfileModalJob: Job? = null
    private var userProfileModalUtils: UserProfileUtils? = null

    init {
        viewModelScope.launch {
            val messageRecord = withContext(Dispatchers.Default) {
                mmsSmsDatabase.getMessageById(messageId)
            }

            if (messageRecord == null) {
                event.send(Event.Finish)
                return@launch
            }

            // listen to conversation and attachments changes
            (threadDb.updateNotifications.filter { it == messageRecord.threadId } as Flow<*>)
                    .debounce(200L)
                    .map {
                        withContext(Dispatchers.Default) {
                            mmsSmsDatabase.getMessageById(messageId)
                        }
                    }
                    .onStart { emit(messageRecord) }
                    .collect { updatedRecord ->
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

                val conversationAddress = threadDb.getRecipientForThreadId(threadId) as Address.Conversable
                val conversation = recipientRepository.getRecipient(conversationAddress)
                val isDeprecatedLegacyGroup = conversationAddress.isLegacyGroup && deprecationManager.isDeprecated


                val errorString = lokiMessageDatabase.getErrorMessage(messageId)

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
                    recipientRepository.getRecipient(Address.fromSerialized(prefs.getLocalNumber()!!))
                } else individualRecipient

                val attachments = slides.map(::Attachment)

                val shouldShowAdminCrown: Boolean = sender.address is Address.WithAccountId  &&
                    (conversation.data as? RecipientData.GroupLike)?.shouldShowAdminCrown(sender.address.accountId) == true

                // we don't want to display image attachments in the carousel if their state isn't done
                val imageAttachments = attachments.filter { it.isDownloaded && it.hasImage }

                // get the helper class for the selected user
                userProfileModalUtils = upmFactory.create(
                    userAddress = sender.address,
                    threadAddress = conversationAddress,
                    scope = viewModelScope
                )

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
                    } else if (dateSent > 0L && errorString == null) {
                        TitledText(R.string.sent, dateUtils.getMessageDateTimeFormattedString(dateSent))
                    } else {
                        null // Not sending or sent? Don't display anything for the "Sent" element.
                    },

                    // Set the "Received" message info TitledText appropriately
                    received = if (dateReceived > 0L && messageRecord.isIncoming && errorString == null) {
                        TitledText(R.string.received, dateUtils.getMessageDateTimeFormattedString(dateReceived))
                    } else {
                        null // Not incoming? Then don't display anything for the "Received" element.
                    },

                    error = errorString?.let { TitledText(context.getString(R.string.theError) + ":", it) },
                    status = status,
                    senderInfo = sender.run {
                        TitledText(
                            if(messageRecord.isOutgoing) context.getString(R.string.you) else displayName(),
                            address.toString()
                        )
                    },
                    senderAvatarData = avatarUtils.getUIDataFromRecipient(sender),
                    senderShowProBadge = proStatusManager.shouldShowProBadge(sender.address),
                    senderHasAdminCrown = shouldShowAdminCrown,
                    senderIsBlinded = IdPrefix.fromValue(sender.address.toString())?.isBlinded() ?: false,
                    thread = conversation,
                    readOnly = isDeprecatedLegacyGroup,
                    proFeatures = proStatusManager.getMessageProFeatures(messageRecord.messageId),
                    proBadgeClickable = !proStatusManager.isCurrentUserPro() // no badge click if the current user is pro
                )
            }
        }
    }

    fun showUserProfileModal() {
        // cancel previous job if any then listen in on the changes
        userProfileModalJob?.cancel()
        userProfileModalJob = viewModelScope.launch {
            userProfileModalUtils?.userProfileModalData?.collect { upmData ->
                _dialogState.update { it.copy(userProfileModal = upmData) }
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

    private fun openImage(index: Int) {
        val state = state.value
        val mmsRecord = state.mmsRecord ?: return
        val slide = mmsRecord.slideDeck.slides[index] ?: return
        // only open to downloaded images
        if (slide.isInProgress || slide.isFailed) return
        if(state.thread == null) return

        viewModelScope.launch {
            MediaPreviewArgs(slide, state.mmsRecord, state.thread.address)
                .let(Event::StartMediaPreview)
                .let { event.send(it) }
        }
    }

    fun retryFailedAttachments(attachments: List<DatabaseAttachment>){
        attachmentDownloadHandler.retryFailedAttachments(attachments)
    }

    fun onCommand(command: Commands) {
        when (command) {
            is Commands.OpenImage -> {
                openImage(command.imageIndex)
            }

            is Commands.ShowProBadgeCTA -> {
                val features = state.value.proFeatures
                _dialogState.update {
                    it.copy(
                        proBadgeCTA = when{
                            features.size > 1 -> ProBadgeCTA.Generic // always show the generic cta when there are more than 1 feature

                            features.contains(LongMessage) -> ProBadgeCTA.LongMessage
                            features.contains(AnimatedAvatar) -> ProBadgeCTA.AnimatedProfile
                            else -> ProBadgeCTA.Generic
                        }
                    )
                }
            }

            is Commands.HideProBadgeCTA -> {
                _dialogState.update { it.copy(proBadgeCTA = null) }
            }

            is Commands.ShowUserProfileModal -> {
                showUserProfileModal()
            }

            is Commands.HideUserProfileModal -> {
                _dialogState.update { it.copy(userProfileModal = null) }
            }

            is Commands.HandleUserProfileCommand -> {
                userProfileModalUtils?.onCommand(command.upmCommand)
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(id: MessageId) : MessageDetailsViewModel
    }
}

sealed interface Commands {
    data class OpenImage(
        val imageIndex: Int
    ): Commands

    object ShowProBadgeCTA: Commands
    object HideProBadgeCTA: Commands

    object ShowUserProfileModal: Commands
    data object HideUserProfileModal: Commands
    data class HandleUserProfileCommand(
        val upmCommand: UserProfileModalCommands
    ): Commands
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
    val senderHasAdminCrown: Boolean = false,
    val senderShowProBadge: Boolean = false,
    val senderIsBlinded: Boolean = false,
    val thread: Recipient? = null,
    val readOnly: Boolean = false,
    val proFeatures: Set<ProStatusManager.MessageProFeature> = emptySet(),
    val proBadgeClickable: Boolean = false,
) {
    val fromTitle = GetString(R.string.from)
    // can reply if we:
    // - are not read only
    // - are not an open group invitation
    // - do not have any errors
    // - are not in a sending state
    val canReply: Boolean get() = !readOnly
            && record?.isOpenGroupInvitation != true
            && error == null
            && record?.isFailed == false
            && record?.isSending == false
    val canDelete: Boolean get() = !readOnly
}

sealed interface ProBadgeCTA {
    data object Generic: ProBadgeCTA
    data object LongMessage: ProBadgeCTA
    data object AnimatedProfile: ProBadgeCTA
}

data class DialogsState(
    val proBadgeCTA: ProBadgeCTA? = null,
    val userProfileModal: UserProfileModalData? = null,
)

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