package org.thoughtcrime.securesms.ui.dialog

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.COMMUNITY_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.URL_KEY
import org.thoughtcrime.securesms.copyURLToClipboard
import org.thoughtcrime.securesms.links.LinkType
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.openUrl
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.PreviewTheme


@Composable
fun LinkAlertDialog(
    data: LinkType,
    onDismissRequest: () -> Unit,
    joinCommunity: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLinkOpened: (String) -> Unit = {},
    onLinkCopied: (String) -> Unit = {},
    content: @Composable () -> Unit = {}
){
    when(data){
        is LinkType.GenericLink ->
            OpenURLAlertDialog(
                onDismissRequest = onDismissRequest,
                modifier = modifier,
                url = data.url,
                onLinkOpened = onLinkOpened,
                onLinkCopied = onLinkCopied,
                content = content
            )

        is LinkType.CommunityLink ->
            CommunityLinkAlertDialog(
                data = data,
                onDismissRequest = onDismissRequest,
                joinCommunity = joinCommunity,
                modifier = modifier
            )
    }
}

@Composable
fun OpenURLAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    url: String,
    onLinkOpened: (String) -> Unit = {},
    onLinkCopied: (String) -> Unit = {},
    content: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val unformattedText = Phrase.from(stringResource(R.string.urlOpenDescription))
        .put(URL_KEY, url).format()


    AlertDialog(
        modifier = modifier,
        title = AnnotatedString(stringResource(R.string.urlOpen)),
        text = annotatedStringResource(text = unformattedText),
        maxLines = 5,
        showCloseButton = true, // display the 'x' button
        buttons = listOf(
            DialogButtonData(
                text = GetString(R.string.open),
                color = LocalColors.current.danger,
                dismissOnClick = false,
                onClick = {
                    if(context.openUrl(url)){
                        onLinkOpened(url)
                        onDismissRequest()
                    }
                }
            ),
            DialogButtonData(
                text = GetString(android.R.string.copyUrl),
                onClick = {
                    onLinkCopied(url)
                    context.copyURLToClipboard(url)
                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                }
            )
        ),
        onDismissRequest = onDismissRequest,
        content = content
    )
}

@Composable
fun CommunityLinkAlertDialog(
    data: LinkType.CommunityLink,
    onDismissRequest: () -> Unit,
    joinCommunity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    //todo comlink I need to verify both the strings and buttons from design
    val context = LocalContext.current
    val title = if (data.joined) {
        stringResource(R.string.communityJoined)
    } else {
        stringResource(R.string.communityJoin)
    }
    val text = if (data.joined) {
        stringResource(R.string.communityJoinedAlready)
    } else {
        Phrase.from(context, R.string.communityJoinDescription)
            .put(COMMUNITY_NAME_KEY, data.name)
            .format()
            .toString()
    }

    val copyButton = DialogButtonData(
        text = GetString(android.R.string.copyUrl),
        onClick = {
            context.copyURLToClipboard(data.url)
            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    )

    val buttons = if (data.joined) {
        listOf(copyButton, DialogButtonData(text = GetString(android.R.string.ok)))
    } else {
        listOf(
            DialogButtonData(
                text = GetString(R.string.join),
                onClick = { joinCommunity(data.url) }
            ),
            copyButton
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = title,
        text = text,
        buttons = buttons,
    )
}

@Preview
@Composable
fun PreviewOpenURLDialog() {
    PreviewTheme {
        OpenURLAlertDialog(
            url = "https://getsession.org/",
            onDismissRequest = {}
        )
    }
}

@Preview
@Composable
fun PreviewNewCommunity() {
    PreviewTheme {
        CommunityLinkAlertDialog(
            data = LinkType.CommunityLink(
                url = "https://getsession.org/",
                name = "Session",
                joined = false,
            ),
            joinCommunity = {},
            onDismissRequest = {},
        )
    }
}

@Preview
@Composable
fun PreviewExistingCommunity() {
    PreviewTheme {
        CommunityLinkAlertDialog(
            data = LinkType.CommunityLink(
                url = "https://getsession.org/",
                name = "Session",
                joined = true,
            ),
            joinCommunity = {},
            onDismissRequest = {},
        )
    }
}