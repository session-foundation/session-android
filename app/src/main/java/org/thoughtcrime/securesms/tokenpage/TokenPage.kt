package org.thoughtcrime.securesms.tokenpage

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.TopCenter
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants.NETWORK_NAME
import org.session.libsession.utilities.NonTranslatableStringConstants.STAKING_REWARD_POOL
import org.session.libsession.utilities.NonTranslatableStringConstants.TOKEN_NAME_LONG
import org.session.libsession.utilities.NonTranslatableStringConstants.TOKEN_NAME_SHORT
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.ICON_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NETWORK_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.STAKING_REWARD_POOL_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TOKEN_NAME_LONG_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.TOKEN_NAME_SHORT_KEY
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.ui.OpenURLAlertDialog
import org.thoughtcrime.securesms.ui.SimplePopup
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.BlurredImage
import org.thoughtcrime.securesms.ui.components.CircularProgressIndicator
import org.thoughtcrime.securesms.ui.components.PrimaryOutlineButtonRect
import org.thoughtcrime.securesms.ui.components.SmallCircularProgressIndicator
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.components.iconExternalLink
import org.thoughtcrime.securesms.ui.components.inlineContentMap
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.bold
import org.thoughtcrime.securesms.ui.verticalScrollbar
import org.thoughtcrime.securesms.util.NumberUtil.formatAbbreviated

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenPage(
    uiState: TokenPageUIState,
    sendCommand: (TokenPageCommand) -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }

    val scrollState = rememberScrollState()

    // Details for the pull-to-refresh & limit-refresh-to-when-we-have-fresh-data mechanisms
    val pullToRefreshState = rememberPullToRefreshState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LocalColors.current.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            BackAppBar(
                title = NETWORK_NAME,
                onBack = onClose,
                modifier = Modifier
                    .qaTag("Page heading")
            )
        },
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
    ) { contentPadding ->

        PullToRefreshBox(
            modifier = Modifier.padding(contentPadding),
            state = pullToRefreshState,
            isRefreshing = uiState.isRefreshing,
            onRefresh = { sendCommand(TokenPageCommand.RefreshData) },
            indicator = {
                // Colour the "spinning arrow" indicator to match our theme
                Indicator(
                    state = pullToRefreshState,
                    isRefreshing = uiState.isRefreshing,
                    containerColor = LocalColors.current.backgroundSecondary,
                    color = LocalColors.current.primary,
                    modifier = Modifier.align(TopCenter)
                )
            }
        ) {
            // This is the main column that contains all elements of the Token Page.
            // It reaches the entire width of the screen and scrolls if there is sufficient content to allow it.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = LocalColors.current.background)
                    // IMPORTANT: Add this `verticalScrollbar` modifier property BEFORE `.verticalScroll(scrollState)`!
                    .verticalScrollbar(
                        state = scrollState
                    )
                    .verticalScroll(scrollState)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = LocalDimensions.current.spacing)
                ) {

                    // The Session Network section is just some text with a link to "Learn More" - this does NOT contain the stats section - that comes next.
                    SessionNetworkInfoSection()

                    Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

                    // Stats section - this outlines the number of nodes in our swarm amongst other details
                    StatsSection(
                        currentSessionNodesInSwarm = uiState.currentSessionNodesInSwarm,
                        currentSessionNodesSecuringMessages = uiState.currentSessionNodesSecuringMessages,
                        showNodeCountsAsRefreshing = uiState.showNodeCountsAsRefreshing,
                        priceDataPopupText = uiState.priceDataPopupText,
                        currentSentPriceUSDString = uiState.currentSentPriceUSDString,
                        networkSecuredByUSDString = uiState.networkSecuredByUSDString,
                        networkSecuredBySENTString = uiState.networkSecuredBySENTString
                    )

                    // Token section that lists the staking pool size, market cap, and a button to learn more about staking
                    SessionTokenSection(
                        currentStakingRewardPoolString = uiState.currentStakingRewardPoolString,
                        showNodeCountsAsRefreshing = uiState.showNodeCountsAsRefreshing,
                        currentMarketCapUSDString = uiState.currentMarketCapUSDString
                    )
                }

                // There is a design idiosyncrasy where the "last updated" text needs to be at the very bottom of the screen
                // when there is no scroll, otherwise it should be docked under the "learn about staking" button with padding
                val hasNoScroll = scrollState.maxValue == 0 || scrollState.maxValue == Int.MAX_VALUE

                Column(
                    modifier = Modifier.padding(horizontal = LocalDimensions.current.spacing)
                        .then(
                            if (hasNoScroll) {
                                Modifier.weight(1f)
                            } else Modifier
                        )
                ) {
                    // Last updated indicator ("Last updated 17m ago" etc.)
                    if (uiState.infoResponseData != null) {
                        // the last updated section should be docked at the bottom when there is no scrolling
                        // or below the button if the content is scrolling
                        if (hasNoScroll) {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

                        Text(
                            text = uiState.lastUpdatedString,
                            textAlign = TextAlign.Center,
                            style = LocalType.current.sessionNetworkHeading,
                            color = LocalColors.current.textSecondary,
                            modifier = modifier
                                .fillMaxWidth()
                                .qaTag("Last updated timestamp")
                        )

                        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
                    }

                }

                Spacer(
                    modifier = Modifier.windowInsetsBottomHeight(WindowInsets.systemBars)
                )
            }
        }
    }
}

@Composable
fun SessionNetworkInfoSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Column(
        modifier = modifier
    ) {
        // 1.) "Session Network" small heading
        Text(
            text = NETWORK_NAME,
            style = LocalType.current.sessionNetworkHeading,
            color = LocalColors.current.textSecondary
        )

        Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))

        // 2.) Session network description
        val sessionNetworkDetailsAnnotatedString = annotatedStringResource(
            highlightColor = LocalColors.current.primaryText,
            text = Phrase.from(context.getText(R.string.sessionNetworkDescription))
                .put(NETWORK_NAME_KEY, NETWORK_NAME)
                .put(TOKEN_NAME_LONG_KEY, TOKEN_NAME_LONG)
                .put(APP_NAME_KEY, context.getString(R.string.app_name))
                .put(ICON_KEY, iconExternalLink)
                .format()
        )

        // Note: We apply the link to the entire box so the user doesn't have to click exactly on the highlighted text.
        var showTheOpenUrlModal by remember { mutableStateOf(false) }
        Text(
            modifier = Modifier
                .clickable { showTheOpenUrlModal = true }
                .qaTag("Learn more link"), // The entire clickable box acts as the link, so that's what I've put the qaTag on
            text = sessionNetworkDetailsAnnotatedString,
            inlineContent = inlineContentMap(LocalType.current.large.fontSize),
            style = LocalType.current.large
        )

        if (showTheOpenUrlModal) {
            OpenURLAlertDialog(
                url = "https://docs.getsession.org/session-network",
                onDismissRequest = { showTheOpenUrlModal = false }
            )
        }
    }
}

// Composable to stack to transparent images on top of each other to create the session nodes display
@Composable
fun StatsImageBox(
    showNodeCountsAsRefreshing: Boolean,
    lineDrawableId: Int,
    circlesDrawableId: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.15f)
            .border(
                width = 1.dp,
                color = LocalColors.current.primary,
                shape = MaterialTheme.shapes.extraSmall
            )
    ) {
        // Draw the waiting dots animation if we're refreshing the node counts..
        if (showNodeCountsAsRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = LocalColors.current.text
            )
        } else {
            // ..otherwise draw the correct image for the number of nodes in our swarm.

            // We draw the white connecting lines first..
            Image(
                painter = painterResource(id = lineDrawableId),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit, // Note: `Fit` keeps the image aspect ratio - `FillBounds` will distort it
                colorFilter = ColorFilter.tint(LocalColors.current.text),
            )

            // ..and THEN we draw the colored circles on top and tint them to the theme's accent colour - BUT
            // we have to cheat if we want a glow effect - so we'll draw a blurred version first, and then draw
            // the non-blurred version on top.
            //
            // Also: On Android API 31 and higher we can just call `.blur` on the modifier. While I did attempt to use an android.renderscript
            // blur for older Android versions it was problematic so has been removed.

            // If we're on a dark theme then provide a blurred version of the node circles drawn beneath our upcoming non-blurred version
            if (!LocalColors.current.isLight) {
                BlurredImage(
                    drawableId = circlesDrawableId,
                    blurRadiusDp = 25f,
                    modifier = Modifier.matchParentSize()
                )
            }

            // Final non-blurred copy of our node circles, tinted to match our theme accent colour
            Image(
                painter = painterResource(id = circlesDrawableId),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(LocalColors.current.primary)
            )
        }
    }
}

// This box shows "Session nodes in your swarm" and "Session Nodes securing your messages" details.
@Composable
fun NodeDetailsBox(
    showNodeCountsAsRefreshing: Boolean,
    numNodesInSwarm: String,
    numNodesSecuringMessages: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appName = context.getString(R.string.app_name)

    val nodesInSwarmAS = annotatedStringResource(
        highlightColor = LocalColors.current.primaryText,
        text = Phrase.from(context, R.string.sessionNetworkNodesSwarm)
            .put(APP_NAME_KEY, appName)
            .format()
    )

    val nodesSecuringMessagesAS = annotatedStringResource(
        highlightColor = LocalColors.current.primaryText,
        text = Phrase.from(context, R.string.sessionNetworkNodesSecuring)
            .put(APP_NAME_KEY, appName)
            .format()
    )

    // This Node Details Box consists of a single column..
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // ..with two rows inside it.
        NodeDetailRow(
            label = nodesInSwarmAS,
            amount = numNodesInSwarm,
            isLoading = showNodeCountsAsRefreshing,
            qaTag = "Your swarm amount"
        )

        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        NodeDetailRow(
            label = nodesSecuringMessagesAS,
            amount = numNodesSecuringMessages,
            isLoading = showNodeCountsAsRefreshing,
            qaTag = "Nodes securing amount"
        )
    }
}

@Composable
fun NodeDetailRow(
    label: AnnotatedString,
    amount: String,
    isLoading: Boolean,
    qaTag: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Each row consists of the text on the left (e.g., "Session Nodes in your swarm")..
        Text(
            text = label,
            style = LocalType.current.h8,
            color = LocalColors.current.text,
            modifier = Modifier.fillMaxWidth(0.62f)
        )

        // ..add a spacer with a weight to push the last element to the far right..
        Spacer(modifier = Modifier.weight(1f))

        // ..and then the actual number of nodes in the swarm on the right.
        if (isLoading) {
            SmallCircularProgressIndicator(
                modifier = Modifier.size(LocalDimensions.current.iconMedium),
                color = LocalColors.current.text
            )
            Spacer(modifier = Modifier.width(LocalDimensions.current.xxsSpacing))
        } else {

            // logic to determine if we should use the short hand version
            var useShort by remember(amount) { mutableStateOf(false) }
            var maxLines by remember(amount) { mutableStateOf(1) }
            val display = if (useShort) amount.toBigDecimal().formatAbbreviated(maxFractionDigits = 0)
            else amount

            Text(
                text = display,
                style = LocalType.current.h3,
                color = LocalColors.current.primaryText,
                maxLines = maxLines,
                onTextLayout = { result ->
                    if (result.hasVisualOverflow && !useShort) {
                        useShort = true // trigger recomposition with short text
                    } else if(result.hasVisualOverflow && useShort) {
                        // if we still overflow with the shorthand, break into multiple lines...
                        maxLines = 2
                    }
                },
                textAlign = TextAlign.End,
                modifier = Modifier.qaTag(qaTag)
            )
        }
    }
}

// Method to grab the relevant pair of images for the StatsImageBox showing the number of nodes in your swarm
fun getNodeImageForSwarmSize(numNodesInOurSwarm: Int): Pair<Int, Int> {
    when (numNodesInOurSwarm) {
        1 -> return Pair(R.drawable.session_node_lines_1, R.drawable.session_nodes_1)
        2 -> return Pair(R.drawable.session_node_lines_2, R.drawable.session_nodes_2)
        3 -> return Pair(R.drawable.session_node_lines_3, R.drawable.session_nodes_3)
        4 -> return Pair(R.drawable.session_node_lines_4, R.drawable.session_nodes_4)
        5 -> return Pair(R.drawable.session_node_lines_5, R.drawable.session_nodes_5)
        6 -> return Pair(R.drawable.session_node_lines_6, R.drawable.session_nodes_6)
        7 -> return Pair(R.drawable.session_node_lines_7, R.drawable.session_nodes_7)
        8 -> return Pair(R.drawable.session_node_lines_8, R.drawable.session_nodes_8)
        9 -> return Pair(R.drawable.session_node_lines_9, R.drawable.session_nodes_9)
        10 -> return Pair(R.drawable.session_node_lines_10, R.drawable.session_nodes_10)
        else -> {
            Log.w(
                "TokenPage",
                "Somehow got an illegal numNodesInOurSwarm value: $numNodesInOurSwarm - using 5 as a fallback"
            )
            return Pair(R.drawable.session_node_lines_5, R.drawable.session_nodes_5)
        }
    }
}

// Stats section that shows the number of nodes in your swarm (along with a visual representation), the
// number of nodes securing your messages, the current SENT token price, and the total USD value securing
// the network.
@Composable
fun StatsSection(
    currentSessionNodesInSwarm: Int,
    currentSessionNodesSecuringMessages: Int,
    showNodeCountsAsRefreshing: Boolean,
    currentSentPriceUSDString: String,
    networkSecuredBySENTString: String,
    networkSecuredByUSDString: String,
    priceDataPopupText: String,
    modifier: Modifier = Modifier
) {
    // First row contains the `StatsImageBox` with the number of nodes in your swap and the text
    // details with that number and the number of nodes securing your messages.
    Row(modifier = modifier.fillMaxWidth()) {

        // On the left we have the node image showing how many nodes are in the user's swarm..
        val (linesDrawable, circlesDrawable) = getNodeImageForSwarmSize(currentSessionNodesInSwarm)
        StatsImageBox(
            showNodeCountsAsRefreshing = showNodeCountsAsRefreshing,
            lineDrawableId = linesDrawable,
            circlesDrawableId = circlesDrawable,
            modifier = Modifier
                .fillMaxWidth(0.45f)
                .qaTag("Swarm image")
        )

        Spacer(modifier = Modifier.width(LocalDimensions.current.xsSpacing))

        // ..and on the right we have the text details of num nodes in swarm and total nodes securing your messages.
        NodeDetailsBox(
            showNodeCountsAsRefreshing = showNodeCountsAsRefreshing,
            numNodesInSwarm = currentSessionNodesInSwarm.toString(),
            numNodesSecuringMessages = currentSessionNodesSecuringMessages.toString(),
            modifier = Modifier
                .fillMaxWidth(1.0f)
                .align(Alignment.CenterVertically)
        )
    }

    Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))

    Row(modifier = Modifier.fillMaxWidth()) {
        var cellHeight by remember(
            currentSentPriceUSDString,
            networkSecuredBySENTString,
            networkSecuredByUSDString
        ) { mutableStateOf(0.dp) }

        val density = LocalDensity.current

        // On the left we have the node image showing how many nodes are in the user's swarm..
        val currentPriceString =
            Phrase.from(LocalContext.current, R.string.sessionNetworkCurrentPrice)
                .put(TOKEN_NAME_SHORT_KEY, TOKEN_NAME_SHORT)
                .format().toString()
        val setOneLineOne = currentPriceString
        val setOneLineTwo = currentSentPriceUSDString
        val setOneLineThree = TOKEN_NAME_LONG

        ThreeLineTextCell(
            setOneLineOne,
            setOneLineTwo,
            setOneLineThree,
            qaTag = "SESH price",
            modifier = Modifier
                .fillMaxWidth(0.45f) // 45% width
                .onGloballyPositioned { coordinates ->
                    // Calculate this cell's height in dp
                    val heightInDp = with(density) { coordinates.size.height.toDp() }
                    // Update cellHeight if this cell is taller
                    if (heightInDp > cellHeight) {
                        cellHeight = heightInDp
                    }
                }
                .height(if (cellHeight > 0.dp) cellHeight else androidx.compose.ui.unit.Dp.Unspecified)
        ) {
            // Mutable state to keep track of whether we should display the "Price data powered by CoinGecko" popup
            var shouldDisplayPopup by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .padding(LocalDimensions.current.xxxsSpacing)
                    .size(15.dp)
                    .align(Alignment.TopEnd)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_circle_help),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(LocalColors.current.text), // Tint the question mark icon to be our text colour (typically white)
                    modifier = Modifier
                        .size(15.dp)
                        .clickable { shouldDisplayPopup = true }
                        .qaTag("Tooltip")
                )

                if (shouldDisplayPopup) {
                    PriceDataSourcePopup(priceDataPopupText, onDismiss = { shouldDisplayPopup = false })
                }
            }
        }

        Spacer(modifier = Modifier.width(LocalDimensions.current.xsSpacing))

        // ..and on the right we have the text details of num nodes in swarm and total nodes securing your messages.
        val setTwoLineOne = LocalContext.current.getString(R.string.sessionNetworkSecuredBy)
        val setTwoLineTwo = networkSecuredBySENTString
        val setTwoLineThree = networkSecuredByUSDString
        ThreeLineTextCell(
            setTwoLineOne,
            setTwoLineTwo,
            setTwoLineThree,
            qaTag = "Network secured amount",
            modifier = Modifier.fillMaxWidth(1.0f)
                .onGloballyPositioned { coordinates ->
                    // Calculate this cell's height in dp
                    val heightInDp = with(density) { coordinates.size.height.toDp() }
                    // Update cellHeight if this cell is taller
                    if (heightInDp > cellHeight) {
                        cellHeight = heightInDp
                    }
                }
                .height(if (cellHeight > 0.dp) cellHeight else androidx.compose.ui.unit.Dp.Unspecified)
        )
    }
}

@Composable
fun RewardPoolAndMarketCapRows(
    showNodeCountsAsRefreshing: Boolean,
    currentStakingRewardPoolString: String,
    currentMarketCapUSDString: String,
    modifier: Modifier = Modifier
) {
    val valueTextColour =
        if (showNodeCountsAsRefreshing) LocalColors.current.textSecondary else LocalColors.current.text

    Column(
        modifier = modifier
            .background(LocalColors.current.background)
    ) {
        // Staking reward pool row
        Row(modifier = Modifier.padding(vertical = LocalDimensions.current.smallSpacing)) {
            Text(
                text = STAKING_REWARD_POOL,
                style = LocalType.current.sessionNetworkHeading.bold(),
                color = LocalColors.current.text,
                modifier = Modifier
                    .fillMaxWidth(0.45f)
            )
            Spacer(modifier = Modifier.width(LocalDimensions.current.spacing))

            Text(
                text = currentStakingRewardPoolString,
                style = LocalType.current.sessionNetworkHeading,
                color = valueTextColour,
                modifier = Modifier
                    .weight(1f)
                    .qaTag("Staking reward pool amount")
            )
        }

        // Thin separator line
        HorizontalDivider(
            thickness = 1.dp,
            color = LocalColors.current.borders
        )

        // Market cap row
        Row(modifier = Modifier.padding(vertical = LocalDimensions.current.smallSpacing)) {
            Text(
                text = LocalContext.current.getString(R.string.sessionNetworkMarketCap),
                color = LocalColors.current.text,
                style = LocalType.current.sessionNetworkHeading.bold(),
                modifier = Modifier
                    .fillMaxWidth(0.45f)
            )
            Spacer(modifier = Modifier.width(LocalDimensions.current.spacing))
            Text(
                text = currentMarketCapUSDString,
                style = LocalType.current.sessionNetworkHeading,
                color = valueTextColour,
                modifier = Modifier
                    .weight(1f)
                    .qaTag("Market cap amount")
            )
        }
    }
}

// Section that shows the current size of the staking reward pool & the market cap
@Composable
fun SessionTokenSection(
    showNodeCountsAsRefreshing: Boolean,
    currentStakingRewardPoolString: String,
    currentMarketCapUSDString: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
    ) {
        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        // 1.) "Session Token" small heading
        Text(
            text = TOKEN_NAME_LONG,
            style = LocalType.current.sessionNetworkHeading,
            color = LocalColors.current.textSecondary
        )

        Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))

        val sessionTokenDescription =
            Phrase.from(LocalContext.current, R.string.sessionNetworkTokenDescription)
                .put(TOKEN_NAME_LONG_KEY, TOKEN_NAME_LONG)
                .put(TOKEN_NAME_SHORT_KEY, TOKEN_NAME_SHORT)
                .put(STAKING_REWARD_POOL_KEY, STAKING_REWARD_POOL)
                .format().toString()

        // Session token description text
        Text(
            text = sessionTokenDescription,
            style = LocalType.current.large,
            color = LocalColors.current.text
        )

        Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))

        // Display the rows that show "Staking Reward Pool" and "Market Cap"
        RewardPoolAndMarketCapRows(
            showNodeCountsAsRefreshing = showNodeCountsAsRefreshing,
            currentStakingRewardPoolString = currentStakingRewardPoolString,
            currentMarketCapUSDString = currentMarketCapUSDString
        )

        Spacer(modifier = Modifier.height(LocalDimensions.current.xxxsSpacing))

        // Finally, add a button that links us to the staging page to learn more
        var showTheOpenUrlModal by remember { mutableStateOf(false) }
        PrimaryOutlineButtonRect(
            text = LocalContext.current.getString(R.string.sessionNetworkLearnAboutStaking),
            modifier = Modifier
                .fillMaxWidth()
                .qaTag("Learn about staking link"),
            onClick = { showTheOpenUrlModal = true }
        )

        if (showTheOpenUrlModal) {
            OpenURLAlertDialog(
                url = "https://docs.getsession.org/session-network/staking",
                onDismissRequest = { showTheOpenUrlModal = false }
            )
        }
    }
}

// Pop-up that displays the source of the price data and when we obtained that data
@Composable
fun PriceDataSourcePopup(
    priceDataPopupText: String,
    onDismiss: () -> Unit
) {
    SimplePopup(
        onDismiss = onDismiss
    ) {
        Text(
            text = priceDataPopupText,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(
                    horizontal = LocalDimensions.current.smallSpacing,
                    vertical = LocalDimensions.current.xxsSpacing
                )
                .qaTag("Tooltip info"),
            style = LocalType.current.small
        )
    }
}

// A cell that contains 3 lines of text, such as "Current SESH Price:", then the string with the price such as "$2.57 USD", and
// finally some footer text like "Session Token ("SESH"). It also has an optional question-mark button which will display a pop-up
// saying "Price information provided by CoinGecko"
@Composable
fun ThreeLineTextCell(
    firstLine: String,
    secondLine: String,
    thirdLine: String,
    qaTag: String,
    modifier: Modifier = Modifier,
    extraContent: @Composable BoxScope.() -> Unit = {},
) {
    // Box that contains everything (text and optional question mark)
    Box(
        modifier = modifier
            .background(
                color = LocalColors.current.backgroundSecondary,
                shape = RoundedCornerShape(LocalDimensions.current.xsSpacing)
            )
    ) {
        extraContent()

        Column(
            modifier = Modifier
                .padding(
                    horizontal = LocalDimensions.current.xsSpacing,
                    vertical = LocalDimensions.current.smallSpacing
                )
        ) {
            // Display string 1 of 3
            Text(
                text = firstLine,
                style = LocalType.current.sessionNetworkHeading,
                color = LocalColors.current.text,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
            )

            // Display string 2 of 3
            Text(
                text = secondLine,
                style = LocalType.current.h5,
                color = LocalColors.current.primaryText,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = LocalDimensions.current.xxxsSpacing)
                    .qaTag(qaTag) // QA tag goes directly on the value line
            )

            Spacer(modifier = Modifier.weight(1f))

            // Display string 3 of 3
            Text(
                text = thirdLine,
                style = LocalType.current.sessionNetworkHeading,
                color = LocalColors.current.textSecondary,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
            )
        }
    }
}

// ---------- PREVIEWS ONLY BELOW THIS POINT ----------

@Preview
@Composable
fun PreviewTokenPage() {
    PreviewTheme {
        TokenPage(
            uiState = TokenPageUIState(
                currentSessionNodesInSwarm = 5,
                currentSessionNodesSecuringMessages = 125349,
                currentSentPriceUSDString = "$1,472.22 USD",
                networkSecuredBySENTString = "12M SENT",
                networkSecuredByUSDString = "$1,234,567 USD",
                currentStakingRewardPool = SerializableBigDecimal(40_000_000),
                currentMarketCapUSDString = "$20,456,259 USD",
                currentStakingRewardPoolString = "40,567,789,654,789 SESH",
                lastUpdatedString = "Last updated 1min ago"
            ),
            sendCommand = { },
            modifier = Modifier,
            onClose = { }
        )
    }
}

@Preview
@Composable
fun PreviewTokenPageLoading() {
    PreviewTheme {
        TokenPage(
            uiState = TokenPageUIState(
                currentSessionNodesInSwarm = 5,
                currentSessionNodesSecuringMessages = 123,
                networkSecuredBySENTString = "12M SENT",
                networkSecuredByUSDString = "$1,234,567 USD",
                currentStakingRewardPool = SerializableBigDecimal(40_000_000),
                showNodeCountsAsRefreshing = true
            ),
            sendCommand = { },
            modifier = Modifier,
            onClose = { }
        )
    }
}

@Preview
@Composable
fun PreviewStatsImageBox1() {
    PreviewTheme {
        val data = TokenPageUIState()
        StatsImageBox(
            showNodeCountsAsRefreshing = data.showNodeCountsAsRefreshing,
            R.drawable.session_node_lines_1,
            R.drawable.session_nodes_1
        )
    }
}

@Preview
@Composable
fun PreviewStatsImageBox2() {
    PreviewTheme {
        StatsImageBox(
            showNodeCountsAsRefreshing = TokenPageUIState().showNodeCountsAsRefreshing,
            R.drawable.session_node_lines_2,
            R.drawable.session_nodes_2
        )
    }
}

@Preview
@Composable
fun PreviewStatsImageBox3() {
    PreviewTheme {
        StatsImageBox(
            showNodeCountsAsRefreshing = TokenPageUIState().showNodeCountsAsRefreshing,
            R.drawable.session_node_lines_3,
            R.drawable.session_nodes_3
        )
    }
}

@Preview
@Composable
fun PreviewStatsImageBox4() {
    PreviewTheme {
        StatsImageBox(
            showNodeCountsAsRefreshing = TokenPageUIState().showNodeCountsAsRefreshing,
            R.drawable.session_node_lines_4,
            R.drawable.session_nodes_4
        )
    }
}

@Preview
@Composable
fun PreviewStatsImageBox5() {
    PreviewTheme {
        StatsImageBox(
            showNodeCountsAsRefreshing = TokenPageUIState().showNodeCountsAsRefreshing,
            R.drawable.session_node_lines_5,
            R.drawable.session_nodes_5
        )
    }
}

@Preview
@Composable
fun PreviewStatsImageBox6() {
    PreviewTheme {
        StatsImageBox(
            showNodeCountsAsRefreshing = TokenPageUIState().showNodeCountsAsRefreshing,
            R.drawable.session_node_lines_6,
            R.drawable.session_nodes_6
        )
    }
}

@Preview
@Composable
fun PreviewStatsImageBox7() {
    PreviewTheme {
        StatsImageBox(
            showNodeCountsAsRefreshing = TokenPageUIState().showNodeCountsAsRefreshing,
            R.drawable.session_node_lines_7,
            R.drawable.session_nodes_7
        )
    }
}

@Preview
@Composable
fun PreviewStatsImageBox8() {
    PreviewTheme {
        StatsImageBox(
            showNodeCountsAsRefreshing = TokenPageUIState().showNodeCountsAsRefreshing,
            R.drawable.session_node_lines_8,
            R.drawable.session_nodes_8
        )
    }
}

@Preview
@Composable
fun PreviewStatsImageBox9() {
    PreviewTheme {
        StatsImageBox(
            showNodeCountsAsRefreshing = TokenPageUIState().showNodeCountsAsRefreshing,
            R.drawable.session_node_lines_9,
            R.drawable.session_nodes_9
        )
    }
}

@Preview
@Composable
fun PreviewStatsImageBox10() {
    PreviewTheme {
        StatsImageBox(
            showNodeCountsAsRefreshing = TokenPageUIState().showNodeCountsAsRefreshing,
            R.drawable.session_node_lines_10,
            R.drawable.session_nodes_10
        )
    }
}

@Preview
@Composable
fun PreviewNodeDetailsBox() {
    // Note: The entire text for both entries shows up in white in the preview,
    // but the "your swarm" and "your messages" parts are displayed in the accent
    // colour in-app.
    PreviewTheme {
        val data = TokenPageUIState()
        NodeDetailsBox(
            showNodeCountsAsRefreshing = data.showNodeCountsAsRefreshing,
            numNodesInSwarm = "5",
            numNodesSecuringMessages = "115",
        )
    }
}

@Preview
@Composable
fun PreviewSessionTokenSection() {
    PreviewTheme {
        val data = TokenPageUIState()
        SessionTokenSection(
            showNodeCountsAsRefreshing = data.showNodeCountsAsRefreshing,
            currentStakingRewardPoolString = data.currentStakingRewardPoolString,
            currentMarketCapUSDString = data.currentMarketCapUSDString
        )
    }
}

@Preview
@Composable
fun PreviewCurrentSentPriceCell() {
    val firstLine = "Current SENT Price:"
    val secondLine = "\$1.23 USD"
    val thirdLine = "Session Token (SENT)"
    PreviewTheme {
        ThreeLineTextCell(firstLine, secondLine, thirdLine, qaTag = "Some QA tag")
    }
}

@Preview
@Composable
fun PreviewSessionNetworkSection() {
    PreviewTheme {
        SessionNetworkInfoSection()
    }
}