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
import org.thoughtcrime.securesms.links.LinkType.CommunityLink.DisplayType.*
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.openUrl
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.PreviewTheme


@Composable
fun LinkAlertDialog(
    data: LinkType,
    onDismissRequest: () -> Unit,
    openOrJoinCommunity: (String) -> Unit,
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
                openOrJoinCommunity = openOrJoinCommunity,
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
    val unformattedText = Phrase.from(context.getText(R.string.urlOpenDescription))
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
    openOrJoinCommunity: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    //todo comlink I need to verify both the strings and buttons from design
    val context = LocalContext.current
    val title = if (data.joined) {
        stringResource(R.string.openCommunity)
    } else {
        stringResource(R.string.communityJoin)
    }
    val text: CharSequence = when(data.displayType){
        CONVERSATION -> {
            if (data.joined) {
                Phrase.from(context, R.string.joinedCommunityOpen)
                    .put(COMMUNITY_NAME_KEY, data.name)
                    .format()
            } else {
                annotatedStringResource(R.string.joinThisCommunity)
            }
        }

        ENTERED -> {
            if (data.joined) {
                Phrase.from(context, R.string.communityUrlOpenEntered)
                    .put(COMMUNITY_NAME_KEY, data.name)
                    .format()
            } else {
                annotatedStringResource(R.string.communityUrlJoinEntered)
            }
        }

        SCANNED -> {
            if (data.joined) {
                Phrase.from(context, R.string.communityUrlOpenScanned)
                    .put(COMMUNITY_NAME_KEY, data.name)
                    .format()
            } else {
                annotatedStringResource(R.string.communitUrlJoinScanned)
            }
        }

        GROUP -> {
            if (data.joined) {
                Phrase.from(context, R.string.groupNameContainedUrlOpenCommunity)
                    .put(COMMUNITY_NAME_KEY, data.name)
                    .format()
            } else {
                annotatedStringResource(R.string.groupNameContainedUrlJoinCommunity)
            }
        }

        SEARCH -> {
            Phrase.from(context, R.string.joinedCommunityOpen)
                .put(COMMUNITY_NAME_KEY, data.name)
                .format()
        }
    }

    val openOrJoinButton = DialogButtonData(
        text = if(data.joined) GetString(R.string.open) else GetString(R.string.join),
        onClick = { openOrJoinCommunity(data.url) }
    )

    val copyUrlButton = if(data.allowCopyUrl){
        DialogButtonData(
            text = GetString(android.R.string.copyUrl),
            onClick = {
                context.copyURLToClipboard(data.url)
                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        )
    } else null

    val buttons = when (data.displayType) {
        CONVERSATION -> listOfNotNull(
            copyUrlButton,
            openOrJoinButton
        )

        else  ->
            listOf(
                DialogButtonData(
                    text = GetString(android.R.string.cancel)
                ),
                openOrJoinButton
            )
    }


    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = annotatedStringResource(title),
        text = annotatedStringResource(text),
        showCloseButton = data.displayType == CONVERSATION,
        buttons = buttons,
    )
}


@Preview
@Composable
fun PreviewNewCommunityConvo() {
    PreviewTheme {
        CommunityLinkAlertDialog(
            data = LinkType.CommunityLink(
                url = "https://getsession.org/",
                name = "Session",
                joined = false,
                displayType = CONVERSATION
            ),
            openOrJoinCommunity = {},
            onDismissRequest = {},
        )
    }
}

@Preview
@Composable
fun PreviewExistingCommunityConvo() {
    PreviewTheme {
        CommunityLinkAlertDialog(
            data = LinkType.CommunityLink(
                url = "https://getsession.org/",
                name = "Session",
                joined = true,
                displayType = CONVERSATION
            ),
            openOrJoinCommunity = {},
            onDismissRequest = {},
        )
    }
}

@Preview
@Composable
fun PreviewNewCommunityScanned() {
    PreviewTheme {
        CommunityLinkAlertDialog(
            data = LinkType.CommunityLink(
                url = "https://getsession.org/",
                name = "Session",
                joined = false,
                displayType = SCANNED
            ),
            openOrJoinCommunity = {},
            onDismissRequest = {},
        )
    }
}

@Preview
@Composable
fun PreviewExistingCommunityScanned() {
    PreviewTheme {
        CommunityLinkAlertDialog(
            data = LinkType.CommunityLink(
                url = "https://getsession.org/",
                name = "Session",
                joined = true,
                displayType = SCANNED
            ),
            openOrJoinCommunity = {},
            onDismissRequest = {},
        )
    }
}

@Preview
@Composable
fun PreviewNewCommunityEntered() {
    PreviewTheme {
        CommunityLinkAlertDialog(
            data = LinkType.CommunityLink(
                url = "https://getsession.org/",
                name = "Session",
                joined = false,
                displayType = ENTERED
            ),
            openOrJoinCommunity = {},
            onDismissRequest = {},
        )
    }
}

@Preview
@Composable
fun PreviewExistingCommunityEntered() {
    PreviewTheme {
        CommunityLinkAlertDialog(
            data = LinkType.CommunityLink(
                url = "https://getsession.org/",
                name = "Session",
                joined = true,
                displayType = ENTERED
            ),
            openOrJoinCommunity = {},
            onDismissRequest = {},
        )
    }
}

@Preview
@Composable
fun PreviewNewCommunityGroup() {
    PreviewTheme {
        CommunityLinkAlertDialog(
            data = LinkType.CommunityLink(
                url = "https://getsession.org/",
                name = "Session",
                joined = false,
                displayType = GROUP
            ),
            openOrJoinCommunity = {},
            onDismissRequest = {},
        )
    }
}

@Preview
@Composable
fun PreviewExistingCommunityGroup() {
    PreviewTheme {
        CommunityLinkAlertDialog(
            data = LinkType.CommunityLink(
                url = "https://getsession.org/",
                name = "Session",
                joined = true,
                displayType = GROUP
            ),
            openOrJoinCommunity = {},
            onDismissRequest = {},
        )
    }
}

@Preview
@Composable
fun PreviewNewCommunitySearch() {
    PreviewTheme {
        CommunityLinkAlertDialog(
            data = LinkType.CommunityLink(
                url = "https://getsession.org/",
                name = "Session",
                joined = true,
                displayType = SEARCH
            ),
            openOrJoinCommunity = {},
            onDismissRequest = {},
        )
    }
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