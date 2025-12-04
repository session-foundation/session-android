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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.groups.ContactItem
import org.thoughtcrime.securesms.groups.GroupMemberState
import org.thoughtcrime.securesms.groups.InviteMembersViewModel
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.ProBadgeText
import org.thoughtcrime.securesms.ui.RadioOption
import org.thoughtcrime.securesms.ui.components.Avatar
import org.thoughtcrime.securesms.ui.components.DialogTitledRadioButton
import org.thoughtcrime.securesms.ui.components.RadioButtonIndicator
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
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
fun MemberItem(
    address: Address,
    title: String,
    avatarUIData: AvatarUIData,
    showAsAdmin: Boolean,
    showProBadge: Boolean,
    modifier: Modifier = Modifier,
    onClick: ((address: Address) -> Unit)? = null,
    subtitle: String? = null,
    subtitleColor: Color = LocalColors.current.textSecondary,
    content: @Composable RowScope.() -> Unit = {},
) {
    var itemModifier = modifier
    if (onClick != null) {
        itemModifier = itemModifier.clickable(onClick = { onClick(address) })
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
            badge = if (showAsAdmin) {
                AvatarBadge.Admin
            } else AvatarBadge.None
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxxsSpacing)
        ) {
            ProBadgeText(
                text = title,
                textStyle = LocalType.current.h8.copy(color = LocalColors.current.text),
                showBadge = showProBadge
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
    address: Address,
    avatarUIData: AvatarUIData,
    title: String,
    onClick: (address: Address) -> Unit,
    showAsAdmin: Boolean,
    showProBadge: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = LocalColors.current.textSecondary,
    showRadioButton: Boolean = true
) {
    MemberItem(
        address = address,
        avatarUIData = avatarUIData,
        title = title,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        onClick = if (enabled) onClick else null,
        showAsAdmin = showAsAdmin,
        showProBadge = showProBadge,
        modifier = modifier
    ) {
        if (showRadioButton) {
            RadioButtonIndicator(
                selected = selected,
                enabled = enabled
            )
        }
    }
}

fun LazyListScope.multiSelectMemberList(
    contacts: List<ContactItem>,
    modifier: Modifier = Modifier,
    onContactItemClicked: (address: Address) -> Unit,
    enabled: Boolean = true,
) {
    items(contacts.size) { index ->
        val contact = contacts[index]
        RadioMemberItem(
            modifier = modifier,
            enabled = enabled,
            selected = contact.selected,
            address = contact.address,
            avatarUIData = contact.avatarUIData,
            title = contact.name,
            showAsAdmin = false,
            showProBadge = contact.showProBadge,
            onClick = { onContactItemClicked(contact.address) }
        )
    }
}

@Composable
fun InviteMembersDialog(
    state: InviteMembersViewModel.InviteContactsDialogState,
    modifier: Modifier = Modifier,
    onInviteClicked: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var shareHistory by remember { mutableStateOf(false) }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = {
            // hide dialog
            onDismiss()
        },
        title = annotatedStringResource(R.string.membersInviteTitle),
        text = annotatedStringResource(state.inviteContactsBody),
        content = {
            DialogTitledRadioButton(
                option = RadioOption(
                    value = Unit,
                    title = GetString(LocalResources.current.getString(R.string.membersInviteShareMessageHistoryDays)),
                    selected = !shareHistory
                )
            ) {
                shareHistory = false
            }

            DialogTitledRadioButton(
                option = RadioOption(
                    value = Unit,
                    title = GetString(LocalResources.current.getString(R.string.membersInviteShareNewMessagesOnly)),
                    selected = shareHistory,
                )
            ) {
                shareHistory = true
            }
        },
        buttons = listOf(
            DialogButtonData(
                text = GetString(state.inviteText),
                color = LocalColors.current.danger,
                dismissOnClick = false,
                onClick = {
                    onDismiss()
                    onInviteClicked(shareHistory)
                }
            ),
            DialogButtonData(
                text = GetString(stringResource(R.string.cancel)),
                onClick = {
                    onDismiss()
                }
            )
        )
    )
}

@Composable
fun ManageMemberItem(
    member: GroupMemberState,
    onClick: (address: Address) -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    RadioMemberItem(
        address = Address.fromSerialized(member.accountId.hexString),
        title = member.name,
        subtitle = member.statusLabel,
        subtitleColor = if (member.highlightStatus) {
            LocalColors.current.danger
        } else {
            LocalColors.current.textSecondary
        },
        showAsAdmin = member.showAsAdmin,
        showProBadge = member.showProBadge,
        avatarUIData = member.avatarUIData,
        onClick = onClick,
        modifier = modifier,
        enabled = true,
        selected = selected,
        showRadioButton = !member.isSelf
    )
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
                        address = Address.fromSerialized(random),
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
                        showProBadge = false,
                    ),
                    ContactItem(
                        address = Address.fromSerialized(random),
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
                        showProBadge = true,
                    )
                ),
                onContactItemClicked = {}
            )
        }
    }
}