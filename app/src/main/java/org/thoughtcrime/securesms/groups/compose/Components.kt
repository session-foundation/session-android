package org.thoughtcrime.securesms.groups.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.Contact
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.groups.ContactItem
import org.thoughtcrime.securesms.ui.Avatar
import org.thoughtcrime.securesms.ui.components.RadioButton
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme


@Composable
fun GroupMinimumVersionBanner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(LocalColors.current.warning)
    ) {
        Text(
            text = stringResource(R.string.groupInviteVersion),
            color = LocalColors.current.textAlert,
            style = LocalType.current.small,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
    }
}

fun LazyListScope.multiSelectMemberList(
    contacts: List<ContactItem>,
    modifier: Modifier = Modifier,
    onContactItemClicked: (accountId: AccountId) -> Unit,
    enabled: Boolean = true,
) {
    items(contacts.size) { index ->
        val contact = contacts[index]
        Column(modifier = modifier) {
            if (index == 0) {
                // Show top divider for the first item only
                HorizontalDivider(color = LocalColors.current.borders)
            }

            RadioButton(
                onClick = { onContactItemClicked(contact.accountID) },
                selected = contact.selected,
                enabled = enabled,
                contentPadding = PaddingValues(
                    vertical = LocalDimensions.current.xxsSpacing,
                    horizontal = LocalDimensions.current.smallSpacing
                )
            ) {
                ContactPhoto(contact.accountID)
                Spacer(modifier = Modifier.size(LocalDimensions.current.smallSpacing))
                MemberName(name = contact.name)
            }

            HorizontalDivider(color = LocalColors.current.borders)
        }
    }
}

@Composable
fun RowScope.MemberName(
    name: String,
    modifier: Modifier = Modifier
) = Text(
    text = name,
    style = LocalType.current.h8,
    color = LocalColors.current.text,
    modifier = modifier
        .weight(1f)
        .align(CenterVertically)
)


@Composable
fun ContactPhoto(sessionId: AccountId) {
    return if (LocalInspectionMode.current) {
        Image(
            painterResource(id = R.drawable.ic_profile_default),
            colorFilter = ColorFilter.tint(LocalColors.current.textSecondary),
            contentScale = ContentScale.Inside,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .border(1.dp, LocalColors.current.borders, CircleShape)
        )
    } else {
        val context = LocalContext.current
        // Ideally we migrate to something that doesn't require recipient, or get contact photo another way
        val recipient = remember(sessionId) {
            Recipient.from(context, Address.fromSerialized(sessionId.hexString), false)
        }
        Avatar(recipient, modifier = Modifier.size(48.dp))
    }
}


@Preview
@Composable
fun PreviewMemberList() {
    val random = "05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234"

    PreviewTheme {
        LazyColumn {
            multiSelectMemberList(
                contacts = listOf(
                    ContactItem(
                        accountID = AccountId(random),
                        name = "Person",
                        selected = false,
                    ),
                    ContactItem(
                        accountID = AccountId(random),
                        name = "Cow",
                        selected = true,
                    )
                ),
                onContactItemClicked = {}
            )
        }
    }
}