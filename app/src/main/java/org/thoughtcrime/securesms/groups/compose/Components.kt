package org.thoughtcrime.securesms.groups.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
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
            .qaTag(stringResource(R.string.AccessibilityId_versionWarning))
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
            .qaTag(stringResource(R.string.AccessibilityId_contact)),
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
                modifier = Modifier.qaTag(stringResource(R.string.AccessibilityId_contact))
            )

            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = LocalType.current.small,
                    color = subtitleColor,
                    modifier = Modifier.qaTag(stringResource(R.string.AccessibilityId_contactStatus))
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
        Column(modifier = modifier) {
            if (index == 0) {
                // Show top divider for the first item only
                HorizontalDivider(color = LocalColors.current.borders)
            }

            RadioMemberItem(
                enabled = enabled,
                selected = contact.selected,
                accountId = contact.accountID,
                avatarUIData = contact.avatarUIData,
                title = contact.name,
                showAsAdmin = false,
                onClick = { onContactItemClicked(contact.accountID) }
            )

            HorizontalDivider(color = LocalColors.current.borders)
        }
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

@Composable
fun AutoResizeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    textAlign: TextAlign = TextAlign.Center,
    minFontSize: TextUnit = 10.sp,
    maxLines: Int = 1,
    precision: TextUnit = 1.sp // Determines search precision
) {
    // Holds the best fitting font size
    var textSize by remember { mutableStateOf(minFontSize) }
    // A text measurer to compute layout metrics without directly rendering text on screen.
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Obtain density to convert between Dp and Sp.
        val density = LocalDensity.current

        // Option 5: Instead of a hardcoded maximum, derive one based on the container's dimensions.
        // For instance, taking the smaller of the maxWidth or maxHeight as an upper bound.
        val dynamicMaxFontSize = with(density) { min(maxWidth, maxHeight).toSp() }

        LaunchedEffect(text, constraints, minFontSize, dynamicMaxFontSize) {
            var low = minFontSize.value
            var high = dynamicMaxFontSize.value
            var bestSize = low
            val precisionValue = precision.value

            // Perform a binary search to find the maximum font size that fits.
            while (high - low > precisionValue) {
                val mid = (low + high) / 2f

                // Measure the text layout at the current midpoint font size.
                val layoutResult = textMeasurer.measure(
                    text = text,
                    style = style.copy(fontSize = mid.sp),
                    constraints = constraints,
                    maxLines = maxLines
                )

                if (layoutResult.didOverflowWidth || layoutResult.didOverflowHeight) {
                    // If the text overflows, reduce the high bound.
                    high = mid
                } else {
                    // If it fits, store this size and try a larger size.
                    bestSize = mid
                    low = mid
                }
            }
            // Update the state to recompose the Text with the optimal font size.
            textSize = bestSize.sp
        }

        // Render the text with the calculated maximum font size.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = textSize,
                style = style,
                textAlign = textAlign,
                maxLines = maxLines,
            )
        }
    }
}

@Preview
@Composable
fun PreviewAutoTextSize() {
    PreviewTheme {
        Column(
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing)
        ) {
            Box (
                modifier = Modifier.size(200.dp).background(Color.DarkGray)
            ){
                AutoResizeText(text = "TO")
            }

            Box (
                modifier = Modifier.size(20.dp).background(Color.DarkGray)
            ){
                AutoResizeText(text = "TO")
            }
        }
    }
}
