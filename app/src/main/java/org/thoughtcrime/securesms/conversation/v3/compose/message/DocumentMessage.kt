package org.thoughtcrime.securesms.conversation.v3.compose.message

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import kotlinx.collections.immutable.persistentListOf
import network.loki.messenger.R
import org.thoughtcrime.securesms.conversation.v3.compose.message.PreviewMessageData.composeContent
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.ui.components.SmallCircularProgressIndicator
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.blackAlpha06


@Composable
fun DocumentMessage(
    data: DocumentMessageData,
    outgoing: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xsSpacing)
    ) {
        // icon box
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .background(blackAlpha06)
                .padding(horizontal = LocalDimensions.current.xsSpacing),
            contentAlignment = Alignment.Center
        ) {
            if (data.loading) {
                SmallCircularProgressIndicator(color = getTextColor(outgoing))
            } else {
                Image(
                    painter = painterResource(id = R.drawable.ic_file),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(getTextColor(outgoing)),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(LocalDimensions.current.iconMedium)
                )
            }
        }

        val padding = defaultMessageBubblePadding()
        Column(
            modifier = Modifier.padding(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
                end = padding.calculateEndPadding(LocalLayoutDirection.current)
            )
        ) {
            Text(
                text = data.name,
                style = LocalType.current.large,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = getTextColor(outgoing)
            )

            Text(
                text = data.size,
                style = LocalType.current.small,
                color = getTextColor(outgoing)
            )
        }
    }
}

data class DocumentMessageData(
    val name: String,
    val size: String,
    val uri: String,
    val loading: Boolean,
)
@Preview
@Composable
fun DocumentMessagePreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LocalDimensions.current.spacing)

        ) {
            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                layout = MessageLayout.OUTGOING,
                contentGroups = PreviewMessageData.documentGroup()
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                avatar = PreviewMessageData.sampleAvatar,
                layout = MessageLayout.INCOMING,
                contentGroups = PreviewMessageData.documentGroup(
                    name = "Document with a really long name that should ellipsize once it reaches the max width"
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                layout = MessageLayout.OUTGOING,
                contentGroups = PreviewMessageData.documentGroup(loading = true)
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                layout = MessageLayout.OUTGOING,
                contentGroups = persistentListOf(
                    composeContent(PreviewMessageData.quote()),
                    composeContent(PreviewMessageData.document(loading = true)),
                )
            ))

            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))

            Message(data = MessageViewData(
                id = MessageId(0, false),
                displayName = "Toto",
                layout = MessageLayout.INCOMING,
                contentGroups = persistentListOf(
                    composeContent(PreviewMessageData.quote()),
                    composeContent(PreviewMessageData.document(loading = true)),
                )
            ))
        }
    }
}
