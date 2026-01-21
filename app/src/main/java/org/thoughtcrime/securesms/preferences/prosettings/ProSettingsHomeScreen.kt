package org.thoughtcrime.securesms.preferences.prosettings

import androidx.annotation.DrawableRes
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.squareup.phrase.Phrase
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.ICON_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.*
import org.thoughtcrime.securesms.pro.ProDataState
import org.thoughtcrime.securesms.pro.ProStatus
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.pro.previewAutoRenewingApple
import org.thoughtcrime.securesms.pro.previewExpiredApple
import org.thoughtcrime.securesms.ui.ActionRowItem
import org.thoughtcrime.securesms.ui.CategoryCell
import org.thoughtcrime.securesms.ui.Divider
import org.thoughtcrime.securesms.ui.IconActionRowItem
import org.thoughtcrime.securesms.ui.ProBadgeText
import org.thoughtcrime.securesms.ui.SpeechBubbleTooltip
import org.thoughtcrime.securesms.ui.SwitchActionRowItem
import org.thoughtcrime.securesms.ui.components.AccentFillButtonRect
import org.thoughtcrime.securesms.ui.components.ExtraSmallCircularProgressIndicator
import org.thoughtcrime.securesms.ui.components.SmallCircularProgressIndicator
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.components.iconExternalLink
import org.thoughtcrime.securesms.ui.components.inlineContentMap
import org.thoughtcrime.securesms.ui.proBadgeColorDisabled
import org.thoughtcrime.securesms.ui.proBadgeColorStandard
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.primaryBlue
import org.thoughtcrime.securesms.ui.theme.primaryOrange
import org.thoughtcrime.securesms.ui.theme.primaryPink
import org.thoughtcrime.securesms.ui.theme.primaryPurple
import org.thoughtcrime.securesms.ui.theme.primaryRed
import org.thoughtcrime.securesms.ui.theme.primaryYellow
import org.thoughtcrime.securesms.util.NumberUtil
import org.thoughtcrime.securesms.util.State


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ProSettingsHomeScreen(
    viewModel: ProSettingsViewModel,
    inSheet: Boolean,
    shouldScrollToTop: Boolean = false,
    onScrollToTopConsumed: () -> Unit = {},
    onBack: () -> Unit,
) {
    val data by viewModel.proSettingsUIState.collectAsState()

    val listState = rememberLazyListState()

    // check if we requested to scroll to the top
    LaunchedEffect(shouldScrollToTop) {
        if (shouldScrollToTop) {
            listState.scrollToItem(0)
            onScrollToTopConsumed()
        }
    }

    ProSettingsHome(
        data = data,
        inSheet = inSheet,
        listState = listState,
        sendCommand = viewModel::onCommand,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ProSettingsHome(
    data: ProSettingsViewModel.ProSettingsState,
    inSheet: Boolean,
    listState: LazyListState,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
) {
    val subscriptionType = data.proDataState.type
    val context = LocalContext.current

    val expiredInMainScreen = subscriptionType is ProStatus.Expired && !inSheet
    val expiredInSheet = subscriptionType is ProStatus.Expired && inSheet

    BaseProSettingsScreen(
        disabled = expiredInMainScreen,
        hideHomeAppBar = inSheet,
        listState = listState,
        onBack = onBack,
        onHeaderClick = {
            // add a click handling if the subscription state is loading or errored
            if(data.proDataState.refreshState !is State.Success<*>){
                sendCommand(OnHeaderClicked(inSheet))
            } else null
        },
        extraHeaderContent = {
            // display extra content if the subscription state is loading or errored
            when(data.proDataState.refreshState){
                is State.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxsSpacing)
                    ) {
                        Text(
                            text = Phrase.from(context.getText(
                                when(subscriptionType){
                                    is ProStatus.Active -> R.string.proStatusLoadingSubtitle
                                    else -> R.string.checkingProStatus
                                }))
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString(),
                            style = LocalType.current.base,
                            color = LocalColors.current.text

                        )

                        ExtraSmallCircularProgressIndicator()
                    }
                }

                is State.Error -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxxsSpacing)
                    ) {
                        Text(
                            text = Phrase.from(context.getText(
                                when(subscriptionType){
                                    is ProStatus.Active -> R.string.proErrorRefreshingStatus
                                    else -> R.string.errorCheckingProStatus
                                }))
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString(),
                            style = LocalType.current.base,
                            color = LocalColors.current.warning

                        )

                        Image(
                            modifier = Modifier.size(LocalDimensions.current.iconXSmall),
                            painter = painterResource(id = R.drawable.ic_triangle_alert),
                            colorFilter = ColorFilter.tint(LocalColors.current.warning),
                            contentDescription = null,
                        )
                    }
                }

                else -> null
            }
        }
    ) {
        // Header for non-pro users or expired users in sheet mode
        if(subscriptionType is ProStatus.NeverSubscribed || expiredInSheet) {
            if(data.proDataState.refreshState !is State.Success){
                Spacer(Modifier.height(LocalDimensions.current.contentSpacing))
            }

            Text(
                text = if(expiredInSheet) Phrase.from(context.getText(R.string.proAccessRenewStart))
                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                    .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                    .format().toString()
                    else Phrase.from(context.getText(R.string.proFullestPotential))
                    .put(APP_NAME_KEY, stringResource(R.string.app_name))
                    .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                    .format().toString(),
                style = LocalType.current.base,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(LocalDimensions.current.spacing))

            Box {
                val enableButon = data.proDataState.refreshState is State.Success
                AccentFillButtonRect(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.theContinue),
                    enabled = enableButon,
                    onClick = { sendCommand(GoToChoosePlan(inSheet)) }
                )
                // the designs require we should still be able to click on the disabled button...
                // this goes against the system the built in ux decisions.
                // To avoid extending the button we will instead add a clickable area above the button,
                // invisible to screen readers as this is purely a visual action in case people try to
                // click in spite of the state being "loading" or "error"
                if (!enableButon) {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .height(LocalDimensions.current.minItemButtonHeight)
                            .semantics {
                                hideFromAccessibility()
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { sendCommand(GoToChoosePlan(inSheet)) }
                            )
                    ) { }
                }
            }
        }

        // Pro Stats
        if(subscriptionType is ProStatus.Active){
            Spacer(Modifier.height(LocalDimensions.current.spacing))
            ProStats(
                data = data.proStats,
                sendCommand = sendCommand,
            )
        }

        // Pro account settings
        if(subscriptionType is ProStatus.Active){
            Spacer(Modifier.height(LocalDimensions.current.smallSpacing))
            ProSettings(
                showProBadge = data.proDataState.showProBadge,
                proStatus = data.proDataState.type,
                subscriptionRefreshState = data.proDataState.refreshState,
                inSheet = inSheet,
                inGracePeriod = data.inGracePeriod,
                expiry = data.subscriptionExpiryLabel,
                sendCommand = sendCommand,
            )
        }

        // Manage Pro - Expired
        if(expiredInMainScreen){
            Spacer(Modifier.height(LocalDimensions.current.spacing))
            ProManage(
                data = subscriptionType,
                subscriptionRefreshState = data.proDataState.refreshState,
                inSheet = inSheet,
                sendCommand = sendCommand,
            )
        }

        // Features
        Spacer(Modifier.height(LocalDimensions.current.spacing))
        ProFeatures(
            data = subscriptionType,
            disabled = expiredInMainScreen,
            sendCommand = sendCommand,
        )

        // do not display the footer in sheet mode
        if(!inSheet){
           ProSettingsFooter(
               proStatus = subscriptionType,
               subscriptionRefreshState = data.proDataState.refreshState,
               inSheet = inSheet,
               sendCommand = sendCommand
           )
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))
        Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProStats(
    modifier: Modifier = Modifier,
    data: State<ProSettingsViewModel.ProStats>,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
){
    CategoryCell(
        modifier = modifier,
        dropShadow = LocalColors.current.isLight,
        title = Phrase.from(LocalContext.current, R.string.proStats)
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format().toString(),
        titleIcon = {
            val tooltipState = rememberTooltipState(isPersistent = true)
            val scope = rememberCoroutineScope()

            SpeechBubbleTooltip(
                text = Phrase.from(LocalContext.current, R.string.proStatsTooltip)
                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                    .format().toString(),
                tooltipState = tooltipState
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_circle_help),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(LocalColors.current.textSecondary),
                    modifier = Modifier
                        .size(LocalDimensions.current.iconXSmall)
                        .clickable {
                            scope.launch {
                                if (tooltipState.isVisible) tooltipState.dismiss() else tooltipState.show()
                            }
                        }
                        .qaTag("Tooltip")
                )
            }
        }
    ){
        // Cell content
        Column(
            modifier = Modifier.fillMaxWidth()
                .then(
                    // make the component clickable is we are in the loading state
                    if (data !is State.Success) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { sendCommand(OnProStatsClicked) }
                    )
                    else Modifier
                )
                .padding(LocalDimensions.current.smallSpacing),
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing)
        ){
            val stats = (data as? State.Success<ProSettingsViewModel.ProStats>)?.value

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xsSpacing)
            ) {
                // Long Messages
                ProStatItem(
                    modifier = Modifier.weight(1f),
                    title = pluralStringResource(
                        R.plurals.proLongerMessagesSent,
                        stats?.longMessages ?: 0,
                        if(stats != null) NumberUtil.getFormattedNumber(stats.longMessages.toLong())
                        else ""
                    ).trim(),
                    loading = data !is State.Success,
                    icon = R.drawable.ic_message_square
                )

                // Pinned Convos
                ProStatItem(
                    modifier = Modifier.weight(1f),
                    title = pluralStringResource(
                        R.plurals.proPinnedConversations,
                        stats?.pinnedConversations ?: 0,
                        if(stats != null) NumberUtil.getFormattedNumber(stats.pinnedConversations.toLong())
                        else ""
                    ).trim(),
                    loading = data !is State.Success,
                    icon = R.drawable.ic_pin
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xsSpacing)
            ) {
                // Pro Badges
                ProStatItem(
                    modifier = Modifier.weight(1f),
                    title = pluralStringResource(
                        R.plurals.proBadgesSent,
                        stats?.proBadges ?: 0,
                        if(stats != null) NumberUtil.getFormattedNumber(stats.proBadges.toLong())
                        else  "",
                        NonTranslatableStringConstants.PRO
                    ).trim(),
                    loading = data !is State.Success,
                    icon = R.drawable.ic_rectangle_ellipsis

                )

                // groups updated
                ProStatItem(
                    modifier = Modifier.weight(1f),
                    title = pluralStringResource(
                        R.plurals.proGroupsUpgraded,
                        stats?.groupsUpdated ?: 0,
                        if(stats != null) NumberUtil.getFormattedNumber(stats.groupsUpdated.toLong())
                        else ""
                    ).trim(),
                    icon = R.drawable.ic_users_group_custom,
                    disabled = true,
                    loading = data !is State.Success,
                    tooltip = stringResource(R.string.proLargerGroupsTooltip)

                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProStatItem(
    modifier: Modifier = Modifier,
    title: String,
    @DrawableRes icon: Int,
    disabled: Boolean = false,
    loading: Boolean = false,
    tooltip: String? = null,
){
    val scope = rememberCoroutineScope()
    val tooltipState = rememberTooltipState(isPersistent = true)

    val disabledState = disabled && !loading

    Row(
        modifier = modifier.then(
            // make the component clickable is there is an edit action
            if (tooltip != null) Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    scope.launch {
                        if (tooltipState.isVisible) tooltipState.dismiss() else tooltipState.show()
                    }
                }
            )
            else Modifier
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing)
    ){
        if(loading){
            SmallCircularProgressIndicator()
        } else {
            Image(
                painter = painterResource(id = icon),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                colorFilter = ColorFilter.tint(
                    if (disabledState) LocalColors.current.textSecondary else LocalColors.current.accent
                )
            )
        }

        Text(
            modifier = Modifier.weight(1f),
            text = title,
            style = LocalType.current.h9,
            color = if(disabledState) LocalColors.current.textSecondary else LocalColors.current.text
        )

        if(tooltip != null && !loading){
            SpeechBubbleTooltip(
                text = tooltip,
                tooltipState = tooltipState
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_circle_help),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(LocalColors.current.textSecondary),
                    modifier = Modifier
                        .size(LocalDimensions.current.iconXSmall)
                        .clickable {
                            scope.launch {
                                if (tooltipState.isVisible) tooltipState.dismiss() else tooltipState.show()
                            }
                        }
                        .qaTag("Tooltip")
                )
            }
        }
    }
}

@Composable
fun ProSettings(
    modifier: Modifier = Modifier,
    showProBadge: Boolean,
    proStatus: ProStatus.Active,
    subscriptionRefreshState: State<Unit>,
    inSheet: Boolean,
    expiry: CharSequence,
    inGracePeriod: Boolean,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
){
    CategoryCell(
        modifier = modifier,
        title = Phrase.from(LocalContext.current, R.string.proSettings)
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format().toString(),
    ) {
        val refunding = proStatus.refundInProgress

        // Cell content
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val chevronIcon: @Composable BoxScope.() -> Unit = {
                Icon(
                    modifier = Modifier.align(Alignment.Center)
                        .size(LocalDimensions.current.iconMedium)
                        .qaTag(R.string.qa_action_item_icon),
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = LocalColors.current.text
                )
            }


            val (subtitle, subColor, icon) = when(subscriptionRefreshState){
                is State.Loading -> Triple<CharSequence, Color, @Composable BoxScope.() -> Unit>(
                        Phrase.from(LocalContext.current, R.string.proAccessLoadingEllipsis)
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .format().toString(),
                            LocalColors.current.text,
                    { SmallCircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }
                    )
                
                is State.Error -> Triple<CharSequence, Color, @Composable BoxScope.() -> Unit>(
                        Phrase.from(LocalContext.current, R.string.errorLoadingProAccess)
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .format().toString(),
                            LocalColors.current.warning, chevronIcon
                    )

                is State.Success<*> -> {
                    Triple<CharSequence, Color, @Composable BoxScope.() -> Unit>(
                        if(refunding) Phrase.from(LocalContext.current, R.string.processingRefundRequest)
                            .put(PLATFORM_KEY, proStatus.providerData.platform)
                            .format().toString()
                        else expiry,
                        if(inGracePeriod) LocalColors.current.warning
                                else LocalColors.current.text,
                        if(refunding){{
                            Icon(
                                modifier = Modifier.align(Alignment.Center)
                                    .size(LocalDimensions.current.iconMedium)
                                    .qaTag(R.string.qa_action_item_icon),
                                painter = painterResource(id = R.drawable.ic_circle_warning_custom),
                                contentDescription = null,
                                tint = LocalColors.current.text
                            )
                        }} else chevronIcon
                    )
                }
            }

            ActionRowItem(
                title = if(refunding) annotatedStringResource(R.string.proRequestedRefund)
                else annotatedStringResource(
                    Phrase.from(LocalContext.current, R.string.updateAccess)
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .format().toString()
                ),
                subtitle = annotatedStringResource(subtitle),
                subtitleColor = subColor,
                enabled = !refunding,
                endContent = {
                    Box(
                        modifier = Modifier.size(LocalDimensions.current.itemButtonIconSpacing)
                    ) {
                        icon()
                    }
                },
                qaTag = R.string.qa_pro_settings_action_update_plan,
                onClick = { sendCommand(GoToChoosePlan(inSheet)) }
            )
            Divider()

            SwitchActionRowItem(
                title = annotatedStringResource(
                    Phrase.from(LocalContext.current, R.string.proBadge)
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .format().toString()
                ),
                subtitle = annotatedStringResource(
                    Phrase.from(LocalContext.current, R.string.proBadgeVisible)
                        .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                        .format().toString()
                ),
                checked = showProBadge,
                qaTag = R.string.qa_pro_settings_action_show_badge,
                onCheckedChange = { sendCommand(SetShowProBadge(it)) }
            )
        }
    }
}

@Composable
fun ProFeatures(
    modifier: Modifier = Modifier,
    data: ProStatus,
    disabled: Boolean,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
) {
    CategoryCell(
        modifier = modifier,
        title = Phrase.from(LocalContext.current, R.string.proBetaFeatures)
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format().toString(),
    ) {
        // Cell content
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(LocalDimensions.current.smallSpacing),
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing)
        ) {
            // Larger Groups (HIDDEN FOR NOW, UNCOMMENT WHEN READY)
           /* ProFeatureItem(
                title = stringResource(R.string.proLargerGroups),
                subtitle = annotatedStringResource(R.string.proLargerGroupsDescription),
                icon = R.drawable.ic_users_round_plus_custom,
                iconGradientStart = primaryGreen,
                iconGradientEnd = primaryBlue,
                expired = data is SubscriptionState.Expired
            )*/

            // Longer messages
            ProFeatureItem(
                title = stringResource(R.string.proLongerMessages),
                subtitle = if(data is ProStatus.Active) annotatedStringResource(R.string.proLongerMessagesDescription)
                else annotatedStringResource(R.string.nonProLongerMessagesDescription),
                icon = R.drawable.ic_message_square,
                iconGradientStart = primaryBlue,
                iconGradientEnd = primaryPurple,
                expired = disabled
            )

            // Unlimited pins
            ProFeatureItem(
                title = stringResource(R.string.proUnlimitedPins),
                subtitle = annotatedStringResource(R.string.proUnlimitedPinsDescription),
                icon = R.drawable.ic_pin,
                iconGradientStart = primaryPurple,
                iconGradientEnd = primaryPink,
                expired = disabled
            )

            // Animated pics
            ProFeatureItem(
                title = stringResource(R.string.proAnimatedDisplayPictures),
                subtitle = annotatedStringResource(R.string.proAnimatedDisplayPicturesDescription),
                icon = R.drawable.ic_square_play,
                iconGradientStart = primaryPink,
                iconGradientEnd = primaryRed,
                expired = disabled
            )

            // Pro badges
            ProFeatureItem(
                title = stringResource(R.string.proBadges),
                subtitle = annotatedStringResource(
                    Phrase.from(LocalContext.current, R.string.proBadgesDescription)
                        .put(APP_NAME_KEY, stringResource(R.string.app_name))
                        .format().toString()
                ),
                icon = R.drawable.ic_rectangle_ellipsis,
                iconGradientStart = primaryRed,
                iconGradientEnd = primaryOrange,
                expired = disabled,
                showProBadge = true,
            )

            // More...
            ProFeatureItem(
                title = stringResource(R.string.plusLoadsMore),
                subtitle = annotatedStringResource(
                    text = Phrase.from(LocalContext.current.getText(R.string.plusLoadsMoreDescription))
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .put(ICON_KEY, iconExternalLink)
                        .format()
                ),
                icon = R.drawable.ic_circle_plus,
                iconGradientStart = primaryOrange,
                iconGradientEnd = primaryYellow,
                expired = disabled,
                onClick = {
                    sendCommand(ShowOpenUrlDialog("https://getsession.org/pro-roadmap"))
                }
            )
        }
    }
}

@Composable
private fun ProFeatureItem(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: AnnotatedString,
    @DrawableRes icon: Int,
    iconGradientStart: Color,
    iconGradientEnd: Color,
    expired: Boolean,
    showProBadge: Boolean = false,
    badgeAtStart: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable { onClick() }
                else Modifier
            ),
        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.smallSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.background(
                brush = Brush.linearGradient(
                    colors = if(expired) listOf(LocalColors.current.disabled, LocalColors.current.disabled)
                            else listOf(iconGradientStart, iconGradientEnd),
                    start = Offset(0f, 0f),        // Top-left corner
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)  // Bottom-right corner
                ),
                shape = MaterialTheme.shapes.extraSmall
            ),
            contentAlignment = Alignment.Center
        ){
            Image(
                painter = painterResource(id = icon),
                contentDescription = null,
                modifier = Modifier
                    .padding(LocalDimensions.current.xsSpacing)
                    .size(LocalDimensions.current.iconMedium),
                colorFilter = ColorFilter.tint(Color.Black)
            )
        }

        Column {
            ProBadgeText(
                text = title,
                textStyle = LocalType.current.h9,
                badgeColors = if(expired) proBadgeColorDisabled() else proBadgeColorStandard(),
                showBadge = showProBadge,
                badgeAtStart = badgeAtStart,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = LocalType.current.small,
                color = LocalColors.current.textSecondary,
                inlineContent = inlineContentMap(
                    textSize = LocalType.current.small.fontSize,
                    imageColor = LocalColors.current.text
                ),
            )
        }
    }
}

@Composable
fun ProManage(
    modifier: Modifier = Modifier,
    data: ProStatus,
    inSheet: Boolean,
    subscriptionRefreshState: State<Unit>,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
){
    CategoryCell(
        modifier = modifier,
        title = Phrase.from(LocalContext.current, R.string.managePro)
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format().toString(),
        dropShadow = LocalColors.current.isLight && data is ProStatus.Expired
    ) {
        // Cell content
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val refundButton: @Composable ()->Unit = {
                IconActionRowItem(
                    title = annotatedStringResource(R.string.requestRefund),
                    titleColor = LocalColors.current.danger,
                    icon = R.drawable.ic_circle_warning_custom,
                    iconColor = LocalColors.current.danger,
                    qaTag = R.string.qa_pro_settings_action_request_refund,
                    onClick = {
                        sendCommand(GoToRefund)
                    }
                )
            }

            val recoverButton: @Composable ()->Unit = {
                IconActionRowItem(
                    title = annotatedStringResource(
                        Phrase.from(LocalContext.current, R.string.proAccessRecover)
                            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                            .format().toString()
                    ),
                    icon = R.drawable.ic_refresh_cw,
                    qaTag = R.string.qa_pro_settings_action_recover_plan,
                    onClick = {
                        sendCommand(RefeshProDetails)
                    }
                )
            }

            when(data){
                is ProStatus.Active.AutoRenewing -> {
                    IconActionRowItem(
                        title = annotatedStringResource(
                            Phrase.from(LocalContext.current, R.string.cancelAccess)
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString()
                        ),
                        titleColor = LocalColors.current.danger,
                        icon = R.drawable.ic_circle_x_custom,
                        iconColor = LocalColors.current.danger,
                        qaTag = R.string.qa_pro_settings_action_cancel_plan,
                        onClick = {
                            sendCommand(GoToCancel)
                        }
                    )
                    Divider()
                    refundButton()
                }

                is ProStatus.Active.Expiring -> {
                    refundButton()
                }

                is ProStatus.NeverSubscribed -> {
                    recoverButton()
                }

                is ProStatus.Expired -> {
                    // the details depend on the loading/error state
                    fun renewIcon(color: Color): @Composable BoxScope.() -> Unit = {
                        Icon(
                            modifier = Modifier.align(Alignment.Center)
                                .size(LocalDimensions.current.iconMedium)
                                .qaTag(R.string.qa_action_item_icon),
                            painter = painterResource(id = R.drawable.ic_circle_plus),
                            contentDescription = null,
                            tint = color
                        )
                    }

                    val (subtitle, subColor, icon) = when(subscriptionRefreshState){
                        is State.Loading -> Triple<CharSequence?, Color, @Composable BoxScope.() -> Unit>(
                            Phrase.from(LocalContext.current, R.string.checkingProStatusEllipsis)
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString(),
                            LocalColors.current.text,
                            { SmallCircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }
                        )

                        is State.Error -> Triple<CharSequence?, Color, @Composable BoxScope.() -> Unit>(
                            Phrase.from(LocalContext.current, R.string.errorCheckingProStatus)
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString(),
                            LocalColors.current.warning, renewIcon(LocalColors.current.text)
                        )

                        is State.Success<*> -> Triple<CharSequence?, Color, @Composable BoxScope.() -> Unit>(
                            null,
                            LocalColors.current.text, renewIcon(LocalColors.current.accentText)
                        )
                    }

                    ActionRowItem(
                        title = annotatedStringResource(
                            Phrase.from(LocalContext.current, R.string.proAccessRenew)
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString()
                        ),
                        titleColor = if(subscriptionRefreshState is State.Success ) LocalColors.current.accentText
                        else LocalColors.current.text,
                        subtitle = if(subtitle == null) null else annotatedStringResource(subtitle),
                        subtitleColor = subColor,
                        endContent = {
                            Box(
                                modifier = Modifier.size(LocalDimensions.current.itemButtonIconSpacing)
                            ) {
                                icon()
                            }
                        },
                        qaTag = R.string.qa_pro_settings_action_renew_plan,
                        onClick = { sendCommand(GoToChoosePlan(inSheet)) }
                    )

                    Divider()
                    recoverButton()
                }
            }
        }
    }
}

@Composable
fun ProSettingsFooter(
    proStatus: ProStatus,
    subscriptionRefreshState: State<Unit>,
    inSheet: Boolean,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
) {
    // Manage Pro - Expired has this in the header so exclude it here
    if(proStatus !is ProStatus.Expired) {
        Spacer(Modifier.height(LocalDimensions.current.smallSpacing))
        ProManage(
            data = proStatus,
            inSheet = inSheet,
            subscriptionRefreshState = subscriptionRefreshState,
            sendCommand = sendCommand,
        )
    }

    // Help
    Spacer(Modifier.height(LocalDimensions.current.spacing))
    CategoryCell(
        title = stringResource(R.string.sessionHelp),
    ) {
        val iconColor = if(proStatus is ProStatus.Expired) LocalColors.current.text
        else LocalColors.current.accentText

        // Cell content
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconActionRowItem(
                title = annotatedStringResource(
                    Phrase.from(LocalContext.current, R.string.proFaq)
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .format().toString()
                ),
                subtitle = annotatedStringResource(
                    Phrase.from(LocalContext.current, R.string.proFaqDescription)
                        .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                        .format().toString()
                ),
                icon = R.drawable.ic_square_arrow_up_right,
                iconSize = LocalDimensions.current.iconMedium,
                iconColor = iconColor,
                qaTag = R.string.qa_pro_settings_action_faq,
                onClick = {
                    sendCommand(ShowOpenUrlDialog("https://getsession.org/faq#pro"))
                }
            )
            Divider()
            IconActionRowItem(
                title = annotatedStringResource(R.string.helpSupport),
                subtitle = annotatedStringResource(
                    Phrase.from(LocalContext.current, R.string.proSupportDescription)
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .format().toString()
                ),
                icon = R.drawable.ic_square_arrow_up_right,
                iconSize = LocalDimensions.current.iconMedium,
                iconColor = iconColor,
                qaTag = R.string.qa_pro_settings_action_support,
                onClick = {
                    sendCommand(ShowOpenUrlDialog(ProStatusManager.URL_PRO_SUPPORT))
                }
            )
        }
    }
}

@Preview
@Composable
fun PreviewProSettingsPro(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        ProSettingsHome(
            data = ProSettingsViewModel.ProSettingsState(
                proDataState = ProDataState(
                    type = previewAutoRenewingApple,
                    refreshState = State.Success(Unit),
                    showProBadge = true,
                ),
            ),
            inSheet = false,
            sendCommand = {},
            listState = rememberLazyListState(),
            onBack = {},
        )
    }
}

@Preview
@Composable
fun PreviewProSettingsProLoading(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        ProSettingsHome(
            data = ProSettingsViewModel.ProSettingsState(
                proDataState = ProDataState(
                    type = previewAutoRenewingApple,
                    refreshState = State.Loading,
                    showProBadge = true,
                ),
            ),
            inSheet = false,
            listState = rememberLazyListState(),
            sendCommand = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
fun PreviewProSettingsProError(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        ProSettingsHome(
            data = ProSettingsViewModel.ProSettingsState(
                proDataState = ProDataState(
                    type = previewAutoRenewingApple,
                    refreshState = State.Error(Exception()),
                    showProBadge = true,
                ),
            ),
            inSheet = false,
            listState = rememberLazyListState(),
            sendCommand = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
fun PreviewProSettingsExpired(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        ProSettingsHome(
            data = ProSettingsViewModel.ProSettingsState(
                proDataState = ProDataState(
                    type = previewExpiredApple,
                    refreshState = State.Success(Unit),
                    showProBadge = true,
                )
            ),
            inSheet = false,
            listState = rememberLazyListState(),
            sendCommand = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
fun PreviewProSettingsExpiredInSheet(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        ProSettingsHome(
            data = ProSettingsViewModel.ProSettingsState(
                proDataState = ProDataState(
                    type = previewExpiredApple,
                    refreshState = State.Success(Unit),
                    showProBadge = true,
                )
            ),
            inSheet = true,
            listState = rememberLazyListState(),
            sendCommand = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
fun PreviewProSettingsExpiredLoading(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        ProSettingsHome(
            data = ProSettingsViewModel.ProSettingsState(
                proDataState = ProDataState(
                    type = previewExpiredApple,
                    refreshState = State.Loading,
                    showProBadge = true,
                )
            ),
            inSheet = false,
            listState = rememberLazyListState(),
            sendCommand = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
fun PreviewProSettingsExpiredError(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        ProSettingsHome(
            data = ProSettingsViewModel.ProSettingsState(
                proDataState = ProDataState(
                    type = previewExpiredApple,
                    refreshState = State.Error(Exception()),
                    showProBadge = true,
                )
            ),
            inSheet = false,
            listState = rememberLazyListState(),
            sendCommand = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
fun PreviewProSettingsNonPro(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        ProSettingsHome(
            data = ProSettingsViewModel.ProSettingsState(
                proDataState = ProDataState(
                    type = ProStatus.NeverSubscribed,
                    refreshState = State.Success(Unit),
                    showProBadge = true,
                )
            ),
            inSheet = false,
            listState = rememberLazyListState(),
            sendCommand = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
fun PreviewProSettingsNonProInSheet(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        ProSettingsHome(
            data = ProSettingsViewModel.ProSettingsState(
                proDataState = ProDataState(
                    type = ProStatus.NeverSubscribed,
                    refreshState = State.Success(Unit),
                    showProBadge = true,
                )
            ),
            inSheet = true,
            listState = rememberLazyListState(),
            sendCommand = {},
            onBack = {},
        )
    }
}