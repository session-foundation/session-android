package org.thoughtcrime.securesms.conversation.v2

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent.ACTION_UP
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.IntentCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewVisibleMessageContentBinding
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.thoughtcrime.securesms.MediaPreviewActivity
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.ui.AnimatedProfilePicProCTA
import org.thoughtcrime.securesms.ui.CTAFeature
import org.thoughtcrime.securesms.ui.CarouselNextButton
import org.thoughtcrime.securesms.ui.CarouselPrevButton
import org.thoughtcrime.securesms.ui.Cell
import org.thoughtcrime.securesms.ui.Divider
import org.thoughtcrime.securesms.ui.GenericProCTA
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.HorizontalPagerIndicator
import org.thoughtcrime.securesms.ui.LargeItemButton
import org.thoughtcrime.securesms.ui.LongMessageProCTA
import org.thoughtcrime.securesms.ui.ProBadgeText
import org.thoughtcrime.securesms.ui.ProCTAFeature
import org.thoughtcrime.securesms.ui.TitledText
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.setComposeContent
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.blackAlpha40
import org.thoughtcrime.securesms.ui.theme.bold
import org.thoughtcrime.securesms.ui.theme.dangerButtonColors
import org.thoughtcrime.securesms.ui.theme.monospace
import org.thoughtcrime.securesms.util.ActivityDispatcher
import org.thoughtcrime.securesms.util.push
import javax.inject.Inject

@AndroidEntryPoint
class MessageDetailActivity : ScreenLockActionBarActivity(), ActivityDispatcher {

    @Inject
    lateinit var storage: StorageProtocol

    private val viewModel: MessageDetailsViewModel by viewModels(extrasProducer = {
        defaultViewModelCreationExtras.withCreationCallback<MessageDetailsViewModel.Factory> {
            it.create(IntentCompat.getParcelableExtra(intent, MESSAGE_ID, MessageId::class.java)!!)
        }
    })

    companion object {
        // Extras
        const val MESSAGE_ID = "message_id"

        const val ON_REPLY = 1
        const val ON_RESEND = 2
        const val ON_DELETE = 3
        const val ON_COPY = 4
        const val ON_SAVE = 5
    }

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)

        title = resources.getString(R.string.messageInfo)

        setComposeContent { MessageDetailsScreen() }

        lifecycleScope.launch {
            viewModel.eventFlow.collect {
                when (it) {
                    Event.Finish -> finish()
                    is Event.StartMediaPreview -> startActivity(
                        MediaPreviewActivity.getPreviewIntent(this@MessageDetailActivity, it.args)
                    )
                }
            }
        }
    }

    override fun dispatchIntent(body: (Context) -> Intent?) {
        body(this)?.let { push(it, false) }
    }

    override fun showDialog(dialogFragment: DialogFragment, tag: String?) {
        dialogFragment.show(supportFragmentManager, tag)
    }

    @Composable
    private fun MessageDetailsScreen() {
        val state by viewModel.stateFlow.collectAsState()
        val dialogState by viewModel.dialogState.collectAsState()

        // can only save if the there is a media attachment which has finished downloading.
        val canSave = state.mmsRecord?.containsMediaSlide() == true
                && state.mmsRecord?.isMediaPending == false

        MessageDetails(
            state = state,
            onReply = if (state.canReply) { { setResultAndFinish(ON_REPLY) } } else null,
            onResend = state.error?.let { { setResultAndFinish(ON_RESEND) } },
            onSave = if(canSave) { { setResultAndFinish(ON_SAVE) } } else null,
            onDelete = if (state.canDelete) { { setResultAndFinish(ON_DELETE) } } else null,
            onCopy = { setResultAndFinish(ON_COPY) },
            sendCommand = { viewModel.onCommand(it) },
            retryFailedAttachments = viewModel::retryFailedAttachments
        )

        MessageDetailDialogs(
            state = dialogState,
            sendCommand = { viewModel.onCommand(it) }
        )
    }

    private fun setResultAndFinish(code: Int) {
        setResult(code, Intent().putExtra(MESSAGE_ID, viewModel.messageId))
        finish()
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun MessageDetails(
    state: MessageDetailsState,
    onReply: (() -> Unit)? = null,
    onResend: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onCopy: () -> Unit = {},
    sendCommand: (Commands) -> Unit,
    retryFailedAttachments: (List<DatabaseAttachment>) -> Unit
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = LocalDimensions.current.smallSpacing),
        verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing)
    ) {
        state.record?.let { message ->
            Column(
                modifier = Modifier.padding(horizontal = LocalDimensions.current.spacing)
            ) {
                AndroidView(
                    factory = { context ->
                        // Inflate the view once
                        ViewVisibleMessageContentBinding.inflate(LayoutInflater.from(context)).root
                    },
                    update = { view ->
                        // Rebind the view whenever state changes.
                        // Retrieve the binding from the view
                        val binding = ViewVisibleMessageContentBinding.bind(view)
                        binding.mainContainerConstraint.apply {
                            bind(
                                message,
                                thread = state.thread!!,
                                downloadPendingAttachment = {}, // the view shouldn't handle this from the details activity
                                retryFailedAttachments = retryFailedAttachments,
                                suppressThumbnails = true
                            )

                            setOnTouchListener { _, event ->
                                if (event.actionMasked == ACTION_UP) onContentClick(event)
                                true
                            }
                        }
                    }
                )

                state.status?.let {
                    Spacer(modifier = Modifier.height(LocalDimensions.current.xxsSpacing))
                    MessageStatus(
                        modifier = Modifier.padding(horizontal = 2.dp),
                        status = it
                    )
                }
            }
        }
        Carousel(state.imageAttachments) { sendCommand(Commands.OpenImage(it)) }
        state.nonImageAttachmentFileDetails?.let { FileDetails(it) }
        CellMetadata(state, sendCommand = sendCommand)
        CellButtons(
            onReply = onReply,
            onResend = onResend,
            onSave = onSave,
            onDelete = onDelete,
            onCopy = onCopy
        )
    }
}

@Composable
fun MessageStatus(
    status: MessageStatus,
    modifier: Modifier = Modifier
){
    val color = if(status.errorStatus) LocalColors.current.danger else LocalColors.current.text
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxsSpacing)
    ) {
        Image(
            modifier = Modifier.size(LocalDimensions.current.iconXSmall),
            painter = painterResource(id = status.icon),
            colorFilter = ColorFilter.tint(color),
            contentDescription = null,
        )

        Text(
            text = status.title,
            style = LocalType.current.extraSmall.copy(color = color)
        )
    }
}

@Preview
@Composable
fun PreviewStatus(){
    PreviewTheme {
        MessageStatus(
            "Failed to send",
            R.drawable.ic_triangle_alert,
            errorStatus = true
        )
    }
}

@Composable
fun CellMetadata(
    state: MessageDetailsState,
    sendCommand: (Commands) -> Unit
) {
    state.apply {
        if (listOfNotNull(sent, received, error, senderInfo).isEmpty()) return
        Cell(modifier = Modifier.padding(horizontal = LocalDimensions.current.spacing)) {
            Column(
                modifier = Modifier.padding(LocalDimensions.current.spacing),
                verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing)
            ) {
                // Message Pro features
                if(proFeatures.isNotEmpty()) {
                    MessageProFeatures(
                        features = proFeatures,
                        badgeClickable = proBadgeClickable,
                        sendCommand = sendCommand
                    )
                }

                // Show the sent details if we're the sender of the message, otherwise show the received details
                if (sent     != null) { TitledText(sent)     }
                if (received != null) { TitledText(received) }

                TitledErrorText(error)
                senderInfo?.let { sender ->
                    TitledView(state.fromTitle) {
                        Row {
                            senderAvatarData?.let {
                                Avatar(
                                    modifier = Modifier
                                        .align(Alignment.CenterVertically),
                                    size = LocalDimensions.current.iconLarge,
                                    data = senderAvatarData
                                )
                                Spacer(modifier = Modifier.width(LocalDimensions.current.smallSpacing))
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxxsSpacing)) {
                                // author
                                ProBadgeText(
                                    text = sender.title.string(),
                                    textStyle = LocalType.current.xl.bold(),
                                    showBadge = state.senderShowProBadge,
                                    onBadgeClick = if(state.proBadgeClickable){{
                                        sendCommand(Commands.ShowProBadgeCTA)
                                    }} else null
                                )

                                sender.text?.let {
                                    Text(
                                        text = it,
                                        style = LocalType.current.base.monospace()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageProFeatures(
    features: Set<ProStatusManager.MessageProFeature>,
    badgeClickable: Boolean,
    sendCommand: (Commands) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ProBadgeText(
            text = stringResource(id = R.string.message),
            textStyle = LocalType.current.xl.bold(),
            badgeAtStart = true,
            onBadgeClick = if(badgeClickable){{
                sendCommand(Commands.ShowProBadgeCTA)
            }} else null
        )

        Text(
            text = Phrase.from(LocalContext.current,R.string.proMessageInfoFeatures)
                .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                .format().toString(),
            style = LocalType.current.large
        )

        features.forEach {
            ProCTAFeature(
                textStyle = LocalType.current.large,
                padding = PaddingValues(),
                data = CTAFeature.Icon(
                    text = when(it){
                        ProStatusManager.MessageProFeature.ProBadge -> Phrase.from(LocalContext.current, R.string.proBadge)
                            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                            .format()
                            .toString()
                        ProStatusManager.MessageProFeature.LongMessage -> stringResource(id = R.string.proIncreasedMessageLengthFeature)
                        ProStatusManager.MessageProFeature.AnimatedAvatar -> stringResource(id = R.string.proAnimatedDisplayPictureFeature)
                    }
                )
            )
        }
    }
}

@Preview
@Composable
fun PreviewMessageProFeatures(){
    PreviewTheme {
        MessageProFeatures(
            features = setOf(
                ProStatusManager.MessageProFeature.ProBadge,
                ProStatusManager.MessageProFeature.LongMessage,
                ProStatusManager.MessageProFeature.AnimatedAvatar
            ),
            badgeClickable = false,
            sendCommand = {}
        )
    }
}

@Composable
fun CellButtons(
    onReply: (() -> Unit)? = null,
    onResend: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onCopy: () -> Unit
) {
    Cell(modifier = Modifier.padding(horizontal = LocalDimensions.current.spacing)) {
        Column {
            onReply?.let {
                LargeItemButton(
                    R.string.reply,
                    R.drawable.ic_reply,
                    onClick = it
                )
                Divider()
            }

            LargeItemButton(
                R.string.copy,
                R.drawable.ic_copy,
                onClick = onCopy
            )
            Divider()

            onSave?.let {
                LargeItemButton(
                    R.string.save,
                    R.drawable.ic_arrow_down_to_line,
                    onClick = it
                )
                Divider()
            }

            onResend?.let {
                LargeItemButton(
                    R.string.resend,
                    R.drawable.ic_repeat_2,
                    onClick = it
                )
                Divider()
            }

            onDelete?.let {
                LargeItemButton(
                    R.string.delete,
                    R.drawable.ic_trash_2,
                    colors = dangerButtonColors(),
                    onClick = it
                )
            }
        }
    }
}

@Composable
fun Carousel(attachments: List<Attachment>, onClick: (Int) -> Unit) {
    if (attachments.isEmpty()) return

    val pagerState = rememberPagerState { attachments.size }

    Column(verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing)) {
        Row {
            CarouselPrevButton(pagerState)
            Box(modifier = Modifier.weight(1f)) {
                CarouselPager(pagerState, attachments, onClick)
                HorizontalPagerIndicator(
                    pagerState = pagerState,
                    modifier = Modifier.padding(bottom = LocalDimensions.current.xxsSpacing)
                )
                ExpandButton(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(LocalDimensions.current.xxsSpacing)
                ) { onClick(pagerState.currentPage) }
            }
            CarouselNextButton(pagerState)
        }
        attachments.getOrNull(pagerState.currentPage)?.fileDetails?.let { FileDetails(it) }
    }
}

@OptIn(
    ExperimentalGlideComposeApi::class
)
@Composable
private fun CarouselPager(
    pagerState: PagerState,
    attachments: List<Attachment>,
    onClick: (Int) -> Unit
) {
    Cell(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
    ) {
        HorizontalPager(state = pagerState) { i ->
            GlideImage(
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clickable { onClick(i) },
                model = if(attachments[i].uri != null) DecryptableStreamUriLoader.DecryptableUri(attachments[i].uri!!)
                else null,
                contentDescription = attachments[i].fileName ?: stringResource(id = R.string.image)
            )
        }
    }
}

@Composable
fun ExpandButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = blackAlpha40,
        modifier = modifier
            .clickable { onClick() },
        contentColor = Color.White,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_maximize_2),
            contentDescription = stringResource(id = R.string.AccessibilityId_expand),
            modifier = Modifier
                .padding(LocalDimensions.current.xxsSpacing)
                .size(LocalDimensions.current.xsSpacing),
        )
    }
}

@Preview
@Composable
fun PreviewMessageDetailsButtons(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        CellButtons(
            onReply = {},
            onResend = {},
            onSave = {},
            onDelete = {},
            onCopy = {}
        )
    }
}

@Preview
@Composable
fun PreviewMessageDetails(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        MessageDetails(
            state = MessageDetailsState(
                imageAttachments = listOf(
                    Attachment(
                        fileDetails = listOf(
                            TitledText(R.string.attachmentsFileId, "Screen Shot 2023-07-06 at 11.35.50 am.png")
                        ),
                        fileName = "Screen Shot 2023-07-06 at 11.35.50 am.png",
                        uri = Uri.parse(""),
                        hasImage = true,
                        isDownloaded = true
                    ),
                    Attachment(
                        fileDetails = listOf(
                            TitledText(R.string.attachmentsFileId, "Screen Shot 2023-07-06 at 11.35.50 am.png")
                        ),
                        fileName = "Screen Shot 2023-07-06 at 11.35.50 am.png",
                        uri = Uri.parse(""),
                        hasImage = true,
                        isDownloaded = true
                    ),
                    Attachment(
                        fileDetails = listOf(
                            TitledText(R.string.attachmentsFileId, "Screen Shot 2023-07-06 at 11.35.50 am.png")
                        ),
                        fileName = "Screen Shot 2023-07-06 at 11.35.50 am.png",
                        uri = Uri.parse(""),
                        hasImage = true,
                        isDownloaded = true
                    )

                ),
                nonImageAttachmentFileDetails = listOf(
                    TitledText(R.string.attachmentsFileId, "Screen Shot 2023-07-06 at 11.35.50 am.png"),
                    TitledText(R.string.attachmentsFileType, "image/png"),
                    TitledText(R.string.attachmentsFileSize, "195.6kB"),
                    TitledText(R.string.attachmentsResolution, "342x312"),
                ),
                sent = TitledText(R.string.sent, "6:12 AM Tue, 09/08/2022"),
                received = TitledText(R.string.received, "6:12 AM Tue, 09/08/2022"),
                error = TitledText(R.string.errorUnknown, "Message failed to send"),
                senderInfo = TitledText("Connor", "d4f1g54sdf5g1d5f4g65ds4564df65f4g65d54"),
                senderShowProBadge = true

            ),
            sendCommand = {},
            retryFailedAttachments = {}
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileDetails(fileDetails: List<TitledText>) {
    if (fileDetails.isEmpty()) return

    Cell(modifier = Modifier.padding(horizontal = LocalDimensions.current.spacing)) {
        FlowRow(
            modifier = Modifier.padding(horizontal = LocalDimensions.current.xsSpacing, vertical = LocalDimensions.current.spacing),
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing)
        ) {
            fileDetails.forEach {
                BoxWithConstraints {
                    TitledText(
                        it,
                        modifier = Modifier
                            .widthIn(min = this.maxWidth.div(2))
                            .padding(horizontal = LocalDimensions.current.xsSpacing)
                            .width(IntrinsicSize.Max)
                    )
                }
            }
        }
    }
}

@Composable
fun TitledErrorText(titledText: TitledText?) {
    TitledText(
        titledText,
        style = LocalType.current.large,
        color = LocalColors.current.danger
    )
}

@Composable
fun TitledText(
    titledText: TitledText?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalType.current.large,
    color: Color = Color.Unspecified
) {
    titledText?.apply {
        TitledView(title, modifier) {
            if (text != null) {
                Text(
                    text,
                    style = style,
                    color = color,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun TitledView(title: GetString, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxxsSpacing)) {
        Text(title.string(), style = LocalType.current.xl.bold())
        content()
    }
}

@Composable
fun MessageDetailDialogs(
    state: DialogsState,
    sendCommand: (Commands) -> Unit
){
    // Pro badge CTAs
    if(state.proBadgeCTA != null){
        when(state.proBadgeCTA){
            is ProBadgeCTA.Generic ->
                GenericProCTA(onDismissRequest = {sendCommand(Commands.HideProBadgeCTA)})

            is ProBadgeCTA.LongMessage ->
                LongMessageProCTA(onDismissRequest = {sendCommand(Commands.HideProBadgeCTA)})

            is ProBadgeCTA.AnimatedProfile ->
                AnimatedProfilePicProCTA(onDismissRequest = {sendCommand(Commands.HideProBadgeCTA)})
        }
    }
}