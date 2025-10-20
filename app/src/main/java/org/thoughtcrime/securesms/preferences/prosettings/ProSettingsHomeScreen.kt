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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
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
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.session.libsession.utilities.recipients.ProStatus
import org.session.libsession.utilities.recipients.shouldShowProBadge
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.*
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.pro.SubscriptionDetails
import org.thoughtcrime.securesms.pro.SubscriptionType
import org.thoughtcrime.securesms.pro.SubscriptionState
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
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
import org.thoughtcrime.securesms.ui.shimmerOverlay
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
import java.time.Duration
import java.time.Instant


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ProSettingsHomeScreen(
    viewModel: ProSettingsViewModel,
    onBack: () -> Unit,
) {
    val data by viewModel.proSettingsUIState.collectAsState()

    ProSettingsHome(
        data = data,
        sendCommand = viewModel::onCommand,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ProSettingsHome(
    data: ProSettingsViewModel.ProSettingsState,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
) {
    val subscriptionType = data.subscriptionState.type
    val context = LocalContext.current

    BaseProSettingsScreen(
        disabled = subscriptionType is SubscriptionType.Expired,
        onBack = onBack,
        onHeaderClick = {
            // add a click handling if the subscription state is loading or errored
            if(data.subscriptionState.refreshState !is State.Success<*>){
                sendCommand(OnHeaderClicked)
            } else null
        },
        extraHeaderContent = {
            // display extra content if the subscription state is loading or errored
            when(data.subscriptionState.refreshState){
                is State.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxsSpacing)
                    ) {
                        Text(
                            text = Phrase.from(context.getText(
                                when(subscriptionType){
                                    is SubscriptionType.Active -> R.string.proStatusLoadingSubtitle
                                    else -> R.string.checkingProStatus
                                    //todo PRO will need to handle never subscribed here
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
                                    is SubscriptionType.Active -> R.string.proErrorRefreshingStatus
                                    else -> R.string.errorCheckingProStatus
                                    //todo PRO will need to handle never subscribed here
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
        // Header for non-pro users
        if(subscriptionType is SubscriptionType.NeverSubscribed) {
            Text(
                text = Phrase.from(context.getText(R.string.proFullestPotential))
                    .put(APP_NAME_KEY, stringResource(R.string.app_name))
                    .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                    .format().toString(),
                style = LocalType.current.base,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(LocalDimensions.current.spacing))

            AccentFillButtonRect(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.theContinue),
                onClick = { sendCommand(GoToChoosePlan) }
            )
        }
        
        // Pro Stats
        if(subscriptionType is SubscriptionType.Active){
            Spacer(Modifier.height(LocalDimensions.current.spacing))
            ProStats(
                data = data.proStats,
                sendCommand = sendCommand,
            )
        }

        // Pro account settings
        if(subscriptionType is SubscriptionType.Active){
            Spacer(Modifier.height(LocalDimensions.current.smallSpacing))
            ProSettings(
                data = subscriptionType,
                subscriptionRefreshState = data.subscriptionState.refreshState,
                expiry = data.subscriptionExpiryLabel,
                sendCommand = sendCommand,
            )
        }

        // Manage Pro - Expired
        if(subscriptionType is SubscriptionType.Expired){
            Spacer(Modifier.height(LocalDimensions.current.spacing))
            ProManage(
                data = subscriptionType,
                subscriptionRefreshState = data.subscriptionState.refreshState,
                sendCommand = sendCommand,
            )
        }

        // Features
        Spacer(Modifier.height(LocalDimensions.current.spacing))
        ProFeatures(
            data = subscriptionType,
            sendCommand = sendCommand,
        )

        // Manage Pro - Pro
        if(subscriptionType is SubscriptionType.Active){
            Spacer(Modifier.height(LocalDimensions.current.smallSpacing))
            ProManage(
                data = subscriptionType,
                subscriptionRefreshState = data.subscriptionState.refreshState,
                sendCommand = sendCommand,
            )
        }

        // Help
        Spacer(Modifier.height(LocalDimensions.current.spacing))
        CategoryCell(
            title = stringResource(R.string.sessionHelp),
        ) {
            val iconColor = if(subscriptionType is SubscriptionType.Expired) LocalColors.current.text
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
    data: SubscriptionType.Active,
    subscriptionRefreshState: State<Unit>,
    expiry: CharSequence,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
){
    CategoryCell(
        modifier = modifier,
        title = Phrase.from(LocalContext.current, R.string.proSettings)
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format().toString(),
    ) {
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

                is State.Success<*> -> Triple<CharSequence, Color, @Composable BoxScope.() -> Unit>(
                        expiry,
                            LocalColors.current.text, chevronIcon
                )
            }

            ActionRowItem(
                title = annotatedStringResource(
                    Phrase.from(LocalContext.current, R.string.updateAccess)
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .format().toString()
                ),
                subtitle = annotatedStringResource(subtitle),
                subtitleColor = subColor,
                endContent = {
                    Box(
                        modifier = Modifier.size(LocalDimensions.current.itemButtonIconSpacing)
                    ) {
                        icon()
                    }
                },
                qaTag = R.string.qa_pro_settings_action_update_plan,
                onClick = { sendCommand(GoToChoosePlan) }
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
                checked = data.proStatus.shouldShowProBadge(),
                qaTag = R.string.qa_pro_settings_action_show_badge,
                onCheckedChange = { sendCommand(SetShowProBadge(it)) }
            )
        }
    }
}

@Composable
fun ProFeatures(
    modifier: Modifier = Modifier,
    data: SubscriptionType,
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
                subtitle = annotatedStringResource(R.string.proLongerMessagesDescription),
                icon = R.drawable.ic_message_square,
                iconGradientStart = primaryBlue,
                iconGradientEnd = primaryPurple,
                expired = data is SubscriptionType.Expired
            )

            // Unlimited pins
            ProFeatureItem(
                title = stringResource(R.string.proUnlimitedPins),
                subtitle = annotatedStringResource(R.string.proUnlimitedPinsDescription),
                icon = R.drawable.ic_pin,
                iconGradientStart = primaryPurple,
                iconGradientEnd = primaryPink,
                expired = data is SubscriptionType.Expired
            )

            // Animated pics
            ProFeatureItem(
                title = stringResource(R.string.proAnimatedDisplayPictures),
                subtitle = annotatedStringResource(R.string.proAnimatedDisplayPicturesDescription),
                icon = R.drawable.ic_square_play,
                iconGradientStart = primaryPink,
                iconGradientEnd = primaryRed,
                expired = data is SubscriptionType.Expired
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
                expired = data is SubscriptionType.Expired,
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
                expired = data is SubscriptionType.Expired,
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
    data: SubscriptionType,
    subscriptionRefreshState: State<Unit>,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
){
    CategoryCell(
        modifier = modifier,
        title = Phrase.from(LocalContext.current, R.string.managePro)
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format().toString(),
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
            when(data){
                is SubscriptionType.Active.AutoRenewing -> {
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

                is SubscriptionType.Active.Expiring -> {
                    refundButton()
                }

                is SubscriptionType.Expired -> {
                    // the details depend on the loading/error state
                    val renewIcon: @Composable BoxScope.() -> Unit = {
                        Icon(
                            modifier = Modifier.align(Alignment.Center)
                                .size(LocalDimensions.current.iconMedium)
                                .qaTag(R.string.qa_action_item_icon),
                            painter = painterResource(id = R.drawable.ic_circle_plus),
                            contentDescription = null,
                            tint = LocalColors.current.text
                        )
                    }

                    val (subtitle, subColor, icon) = when(subscriptionRefreshState){
                        is State.Loading -> Triple<CharSequence?, Color, @Composable BoxScope.() -> Unit>(
                            //todo PRO need the ellipsis version of this string
                            Phrase.from(LocalContext.current, R.string.checkingProStatus)
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString(),
                            LocalColors.current.text,
                            { SmallCircularProgressIndicator(modifier = Modifier.align(Alignment.Center)) }
                        )

                        is State.Error -> Triple<CharSequence?, Color, @Composable BoxScope.() -> Unit>(
                            Phrase.from(LocalContext.current, R.string.errorCheckingProStatus)
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString(),
                            LocalColors.current.warning, renewIcon
                        )

                        is State.Success<*> -> Triple<CharSequence?, Color, @Composable BoxScope.() -> Unit>(
                            null,
                            LocalColors.current.text, renewIcon
                        )
                    }

                    ActionRowItem(
                        title = annotatedStringResource(
                            Phrase.from(LocalContext.current, R.string.proAccessRenew)
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString()
                        ),
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
                        onClick = { sendCommand(GoToChoosePlan) }
                    )

                    Divider()
                    IconActionRowItem(
                        title = annotatedStringResource(
                            Phrase.from(LocalContext.current, R.string.proAccessRecover)
                                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                                .format().toString()
                        ),
                        icon = R.drawable.ic_refresh_cw,
                        qaTag = R.string.qa_pro_settings_action_request_refund,
                        onClick = {
                            //todo PRO implement
                        }
                    )
                }

                is SubscriptionType.NeverSubscribed -> {}
            }
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
                subscriptionState = SubscriptionState(
                    type = SubscriptionType.Active.AutoRenewing(
                        proStatus = ProStatus.Pro(
                            visible = true,
                            validUntil = Instant.now() + Duration.ofDays(14),
                        ),
                        duration = ProSubscriptionDuration.THREE_MONTHS,
                        subscriptionDetails = SubscriptionDetails(
                            device = "iPhone",
                            store = "Apple App Store",
                            platform = "Apple",
                            platformAccount = "Apple Account",
                            subscriptionUrl = "https://www.apple.com/account/subscriptions",
                            refundUrl = "https://www.apple.com/account/subscriptions",
                        )
                    ),
                    refreshState = State.Success(Unit),
                ),
            ),
            sendCommand = {},
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
                subscriptionState = SubscriptionState(
                    type = SubscriptionType.Active.AutoRenewing(
                        proStatus = ProStatus.Pro(
                            visible = true,
                            validUntil = Instant.now() + Duration.ofDays(14),
                        ),
                        duration = ProSubscriptionDuration.THREE_MONTHS,
                        subscriptionDetails = SubscriptionDetails(
                            device = "iPhone",
                            store = "Apple App Store",
                            platform = "Apple",
                            platformAccount = "Apple Account",
                            subscriptionUrl = "https://www.apple.com/account/subscriptions",
                            refundUrl = "https://www.apple.com/account/subscriptions",
                        )
                    ),
                    refreshState = State.Loading,
                ),
            ),
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
                subscriptionState = SubscriptionState(
                    type = SubscriptionType.Active.AutoRenewing(
                        proStatus = ProStatus.Pro(
                            visible = true,
                            validUntil = Instant.now() + Duration.ofDays(14),
                        ),
                        duration = ProSubscriptionDuration.THREE_MONTHS,
                        subscriptionDetails = SubscriptionDetails(
                            device = "iPhone",
                            store = "Apple App Store",
                            platform = "Apple",
                            platformAccount = "Apple Account",
                            subscriptionUrl = "https://www.apple.com/account/subscriptions",
                            refundUrl = "https://www.apple.com/account/subscriptions",
                        )
                    ),
                    refreshState = State.Error(Exception()),
                ),
            ),
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
                subscriptionState = SubscriptionState(
                    type = SubscriptionType.Expired(
                        expiredAt = Instant.now() - Duration.ofDays(14),
                        SubscriptionDetails(
                        device = "iPhone",
                        store = "Apple App Store",
                        platform = "Apple",
                        platformAccount = "Apple Account",
                        subscriptionUrl = "https://www.apple.com/account/subscriptions",
                        refundUrl = "https://www.apple.com/account/subscriptions",
                    )),
                    refreshState = State.Success(Unit),
                )
            ),
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
                subscriptionState = SubscriptionState(
                    type = SubscriptionType.Expired(
                        expiredAt = Instant.now() - Duration.ofDays(14),
                        SubscriptionDetails(
                        device = "iPhone",
                        store = "Apple App Store",
                        platform = "Apple",
                        platformAccount = "Apple Account",
                        subscriptionUrl = "https://www.apple.com/account/subscriptions",
                        refundUrl = "https://www.apple.com/account/subscriptions",
                    )),
                    refreshState = State.Loading,
                )
            ),
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
                subscriptionState = SubscriptionState(
                    type = SubscriptionType.Expired(
                        expiredAt = Instant.now() - Duration.ofDays(14),
                        SubscriptionDetails(
                        device = "iPhone",
                        store = "Apple App Store",
                        platform = "Apple",
                        platformAccount = "Apple Account",
                        subscriptionUrl = "https://www.apple.com/account/subscriptions",
                        refundUrl = "https://www.apple.com/account/subscriptions",
                    )),
                    refreshState = State.Error(Exception()),
                )
            ),
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
                subscriptionState = SubscriptionState(
                    type = SubscriptionType.NeverSubscribed,
                    refreshState = State.Success(Unit),
                )
            ),
            sendCommand = {},
            onBack = {},
        )
    }
}