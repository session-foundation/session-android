package org.thoughtcrime.securesms.groups.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import network.loki.messenger.R
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.groups.ContactItem
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.components.RadioButtonIndicator
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.ui.theme.primaryOrange
import org.thoughtcrime.securesms.util.AvatarBadge
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement


@Composable
fun GroupMinimumVersionBanner(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.groupInviteVersion),
        color = LocalColors.current.textAlert,
        style = LocalType.current.small,
        maxLines = 2,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .background(primaryOrange)
            .fillMaxWidth()
            .padding(
                horizontal = LocalDimensions.current.spacing,
                vertical = LocalDimensions.current.xxxsSpacing
            )
            .qaTag(R.string.AccessibilityId_versionWarning)
    )
}

@Composable
fun  MemberItem(
    accountId: AccountId,
    title: String,
    avatarUIData: AvatarUIData,
    showAsAdmin: Boolean,
    modifier: Modifier = Modifier,
    onClick: ((accountId: AccountId) -> Unit)? = null,
    subtitle: String? = null,
    subtitleColor: Color = LocalColors.current.textSecondary,
    content: @Composable RowScope.() -> Unit = {},
) {
    var itemModifier = modifier
    if(onClick != null){
        itemModifier = itemModifier.clickable(onClick = { onClick(accountId) })
    }

    Row(
        modifier = itemModifier
            .padding(
                horizontal = LocalDimensions.current.smallSpacing,
                vertical = LocalDimensions.current.xsSpacing
            )
            .qaTag(R.string.AccessibilityId_contact),
        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing),
        verticalAlignment = CenterVertically,
    ) {
        Avatar(
            size = LocalDimensions.current.iconLarge,
            data = avatarUIData,
            badge = if (showAsAdmin) { AvatarBadge.Admin } else AvatarBadge.None
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxxsSpacing)
        ) {
            Text(
                style = LocalType.current.h8,
                text = title,
                color = LocalColors.current.text,
                modifier = Modifier.qaTag(R.string.AccessibilityId_contact)
            )

            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = LocalType.current.small,
                    color = subtitleColor,
                    modifier = Modifier.qaTag(R.string.AccessibilityId_contactStatus)
                )
            }
        }

        content()
    }
}

@Composable
fun RadioMemberItem(
    enabled: Boolean,
    selected: Boolean,
    accountId: AccountId,
    avatarUIData: AvatarUIData,
    title: String,
    onClick: (accountId: AccountId) -> Unit,
    showAsAdmin: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = LocalColors.current.textSecondary
) {
    MemberItem(
        accountId = accountId,
        avatarUIData = avatarUIData,
        title = title,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        onClick = if(enabled) onClick else null,
        showAsAdmin = showAsAdmin,
        modifier = modifier
    ){
        RadioButtonIndicator(
            selected = selected,
            enabled = enabled
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
        RadioMemberItem(
            modifier = modifier,
            enabled = enabled,
            selected = contact.selected,
            accountId = contact.accountID,
            avatarUIData = contact.avatarUIData,
            title = contact.name,
            showAsAdmin = false,
            onClick = { onContactItemClicked(contact.accountID) }
        )
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
                        avatarUIData = AvatarUIData(
                            listOf(
                                AvatarUIElement(
                                    name = "TOTO",
                                    color = primaryBlue
                                )
                            )
                        ),
                        selected = false,
                    ),
                    ContactItem(
                        accountID = AccountId(random),
                        name = "Cow",
                        avatarUIData = AvatarUIData(
                            listOf(
                                AvatarUIElement(
                                    name = "TOTO",
                                    color = primaryBlue
                                )
                            )
                        ),
                        selected = true,
                    )
                ),
                onContactItemClicked = {}
            )
        }
    }
}