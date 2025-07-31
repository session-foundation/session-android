package org.thoughtcrime.securesms.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.ProBadgeText
import org.thoughtcrime.securesms.ui.SearchBar
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.ui.theme.primaryOrange
import org.thoughtcrime.securesms.util.AvatarUIData
import org.thoughtcrime.securesms.util.AvatarUIElement

/**
 * A fully Compose implementation of the conversation top bar
 * with HorizontalPager for settings and dot indicators
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationAppBar(
    data: ConversationAppBarData,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onSearchQueryClear: () -> Unit,
    onSearchCanceled: () -> Unit,
    onBackPressed: () -> Unit,
    onCallPressed: () -> Unit,
    onAvatarPressed: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box (
        modifier = modifier
    ) {
        // cross fade between the default app bar and the search bar
        Crossfade(targetState = data.showSearch) { showSearch ->
            when(showSearch){
                false -> {
                    val pagerState = rememberPagerState(pageCount = { data.pagerData.size })

                    CenterAlignedTopAppBar(
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        title = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                var titleModifier: Modifier = Modifier
                                // we want the title to also open the UCS, but we will follow
                                // the logic for the avatar, so if the avatar isn't showing,
                                // do not apply the onClick
                                if(data.showAvatar) {
                                    titleModifier = titleModifier.clickable{ onAvatarPressed() }
                                }

                                ProBadgeText(
                                    modifier = titleModifier.qaTag(R.string.AccessibilityId_conversationTitle),
                                    text = data.title,
                                    showBadge = data.showProBadge
                                )

                                if (data.pagerData.isNotEmpty()) {
                                    // Settings content pager
                                    ConversationSettingsPager(
                                        modifier = Modifier.padding(top = 2.dp)
                                            .fillMaxWidth(0.8f),
                                        pages = data.pagerData,
                                        pagerState = pagerState
                                    )

                                    // Dot indicators
                                    PagerIndicator(
                                        modifier = Modifier.padding(top = 2.dp),
                                        pageCount = data.pagerData.size,
                                        currentPage = pagerState.currentPage
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            AppBarBackIcon(onBack = onBackPressed)
                        },
                        actions = {
                            if (data.showCall) {
                                IconButton(
                                    onClick = onCallPressed
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_phone),
                                        contentDescription = stringResource(id = R.string.AccessibilityId_call),
                                        tint = LocalColors.current.text,
                                        modifier = Modifier.size(LocalDimensions.current.iconMedium)
                                    )
                                }
                            }

                            // Avatar
                            if (data.showAvatar) {
                                Avatar(
                                    modifier = Modifier.qaTag(R.string.qa_conversation_avatar)
                                        .padding(
                                            start = if(data.showCall) 0.dp else LocalDimensions.current.xsSpacing,
                                            end = LocalDimensions.current.xsSpacing
                                        )
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = ripple(bounded = false, radius = LocalDimensions.current.iconLargeAvatar/2),
                                            onClick = onAvatarPressed
                                        ),
                                    size = LocalDimensions.current.iconLargeAvatar,
                                    data = data.avatarUIData
                                )
                            }
                        },
                        colors = appBarColors(LocalColors.current.background)
                    )
                }

                true -> {
                    Row(
                        modifier = Modifier.padding(horizontal = LocalDimensions.current.smallSpacing)
                            .heightIn(min = LocalDimensions.current.appBarHeight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {

                        val focusRequester = remember { FocusRequester() }
                        LaunchedEffect (Unit) {
                            focusRequester.requestFocus()
                        }

                        SearchBar(
                            query = searchQuery,
                            onValueChanged = onSearchQueryChanged,
                            onClear = onSearchQueryClear,
                            placeholder = stringResource(R.string.search),
                            modifier = Modifier.weight(1f)
                                .focusRequester(focusRequester),
                            backgroundColor = LocalColors.current.backgroundSecondary,
                        )

                        Spacer(Modifier.width(LocalDimensions.current.xsSpacing))

                        Text(
                            modifier = Modifier.qaTag(R.string.qa_conversation_search_cancel)
                                .clickable {
                                    onSearchCanceled()
                                },
                            text = stringResource(R.string.cancel),
                            style = LocalType.current.large,
                        )
                    }
                }
            }

        }
    }
}

/**
 * Overall data class for convo app bar data
 */
data class ConversationAppBarData(
    val title: String,
    val pagerData: List<ConversationAppBarPagerData>,
    val showProBadge: Boolean = false,
    val showAvatar: Boolean = false,
    val showCall: Boolean = false,
    val showSearch: Boolean = false,
    val avatarUIData: AvatarUIData
)

/**
 * Data class representing a pager item data
 */
data class ConversationAppBarPagerData(
    val title: String,
    val action: () -> Unit,
    @DrawableRes val icon: Int? = null,
    val qaTag: String? = null
)

/**
 * Horizontal pager for app bar
 */
@Composable
private fun ConversationSettingsPager(
    pages: List<ConversationAppBarPagerData>,
    pagerState: PagerState,
    modifier: Modifier = Modifier
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
    ) { page ->
        Row (
            modifier = Modifier.fillMaxWidth()
                .qaTag(pages[page].qaTag ?: pages[page].title)
                .clickable {
                    pages[page].action()
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // '<' icon
            if(pages.size > 1) {
                Image(
                    modifier = Modifier.size(12.dp),
                    painter = painterResource(id = R.drawable.ic_chevron_left),
                    colorFilter = ColorFilter.tint(LocalColors.current.text),
                    contentDescription = null,
                )
            }

            // optional icon
            if(pages[page].icon != null) {
                Spacer(modifier = Modifier.size(LocalDimensions.current.xxxsSpacing))
                Image(
                    modifier = Modifier.size(LocalDimensions.current.iconXXSmall),
                    painter = painterResource(id = pages[page].icon!!),
                    colorFilter = ColorFilter.tint(LocalColors.current.text),
                    contentDescription = null,
                )
            }

            // Page name
            Text(
                modifier = Modifier.padding(horizontal = LocalDimensions.current.xxxsSpacing),
                text = pages[page].title,
                textAlign = TextAlign.Center,
                color = LocalColors.current.text,
                style = LocalType.current.extraSmall
            )

            // '>' icon
            if(pages.size > 1) {
                Image(
                    modifier = Modifier.size(12.dp).rotate(180f),
                    painter = painterResource(id = R.drawable.ic_chevron_left),
                    colorFilter = ColorFilter.tint(LocalColors.current.text),
                    contentDescription = null,
                )
            }
        }
    }
}

/**
 * Dots indicator for the pager
 */
@Composable
private fun PagerIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    if (pageCount <= 1) return

    Row(
        modifier = modifier
            .height(LocalDimensions.current.xsSpacing),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { page ->
            val isSelected = page == currentPage

            Box(
                modifier = Modifier
                    .size(
                        width = 4.dp,
                        height = 4.dp
                    )
                    .clip(CircleShape)
                    .background(
                        if (isSelected)
                            LocalColors.current.text
                        else
                            LocalColors.current.text.copy(alpha = 0.3f)
                    )
            )
        }
    }
}


/**
 * Preview parameters for ConversationTopBar
 */
class ConversationTopBarPreviewParams(
    val title: String,
    val settingsPagesCount: Int,
    val isCallAvailable: Boolean,
    val showAvatar: Boolean,
    val showSearch: Boolean = false,
    val showProBadge: Boolean = false
)

/**
 * Provider for ConversationTopBar preview parameters
 */
class ConversationTopBarParamsProvider : PreviewParameterProvider<ConversationTopBarPreviewParams> {
    override val values = sequenceOf(
        // Basic conversation with no settings
        ConversationTopBarPreviewParams(
            title = "Alice Smith",
            settingsPagesCount = 0,
            isCallAvailable = false,
            showAvatar = true
        ),
        // Basic conversation with no settings + Pro
        ConversationTopBarPreviewParams(
            title = "Alice Smith",
            settingsPagesCount = 0,
            isCallAvailable = false,
            showAvatar = true,
            showProBadge = true
        ),
        // Basic conversation with no settings
        ConversationTopBarPreviewParams(
            title = "Alice Smith",
            settingsPagesCount = 0,
            isCallAvailable = true,
            showAvatar = true
        ),
        // Basic conversation with no settings
        ConversationTopBarPreviewParams(
            title = "Alice Smith",
            settingsPagesCount = 3,
            isCallAvailable = true,
            showAvatar = true
        ),
        // Long title without call button
        ConversationTopBarPreviewParams(
            title = "Really Long Conversation Title That Should Ellipsize",
            settingsPagesCount = 0,
            isCallAvailable = false,
            showAvatar = true
        ),
        // Long title without call button + Pro
        ConversationTopBarPreviewParams(
            title = "Really Long Conversation Title That Should Ellipsize",
            settingsPagesCount = 0,
            isCallAvailable = false,
            showAvatar = true,
            showProBadge = true
        ),
        // Long title with call button
        ConversationTopBarPreviewParams(
            title = "Really Long Conversation Title That Should Ellipsize",
            settingsPagesCount = 0,
            isCallAvailable = true,
            showAvatar = true
        ),
        // Long title with call button + Pro
        ConversationTopBarPreviewParams(
            title = "Really Long Conversation Title That Should Ellipsize",
            settingsPagesCount = 0,
            isCallAvailable = true,
            showAvatar = true,
            showProBadge = true
        ),
        // With settings pages and all options
        ConversationTopBarPreviewParams(
            title = "Group Chat",
            settingsPagesCount = 3,
            isCallAvailable = true,
            showAvatar = true
        ),
        // No avatar
        ConversationTopBarPreviewParams(
            title = "New Contact",
            settingsPagesCount = 0,
            isCallAvailable = false,
            showAvatar = false
        ),
        // search
        ConversationTopBarPreviewParams(
            title = "Alice Smith",
            settingsPagesCount = 0,
            isCallAvailable = false,
            showAvatar = true,
            showSearch = true
        ),
    )
}


/**
 * Preview for ConversationTopBar with different configurations
 */
@Preview(showBackground = true)
@Composable
fun ConversationTopBarPreview(
    @PreviewParameter(ConversationTopBarParamsProvider::class) params: ConversationTopBarPreviewParams
) {
    PreviewTheme {
        // Create sample settings pages
        val settingsPages = List(params.settingsPagesCount) { index ->
            ConversationAppBarPagerData(
                title = "Settings $index",
                icon = R.drawable.ic_clock_11,
                action = {}
            )
        }

        ConversationAppBar(
            data = ConversationAppBarData(
                title = params.title,
                pagerData = settingsPages,
                showAvatar = params.showAvatar,
                showCall = params.isCallAvailable,
                showSearch = params.showSearch,
                showProBadge = params.showProBadge,
                avatarUIData = AvatarUIData(
                    listOf(
                        AvatarUIElement(
                            name = "TO",
                            color = primaryBlue
                        ),
                        AvatarUIElement(
                            name = "TA",
                            color = primaryOrange
                        )
                    )
                )
            ),
            onBackPressed = { /* no-op for preview */ },
            onCallPressed = { /* no-op for preview */ },
            onAvatarPressed = { /* no-op for preview */ },
            searchQuery = "",
            onSearchQueryChanged = {},
            onSearchQueryClear = {},
            onSearchCanceled = {},
        )
    }
}

