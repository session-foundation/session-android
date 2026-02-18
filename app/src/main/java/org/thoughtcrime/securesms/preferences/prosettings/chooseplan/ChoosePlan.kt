package org.thoughtcrime.securesms.preferences.prosettings.chooseplan

import android.icu.util.MeasureUnit
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.squareup.phrase.Phrase
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants.ACTION_TYPE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.ACTIVATION_TYPE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.CURRENT_PLAN_LENGTH_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.DATE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.ENTITY_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.ICON_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.MONTHLY_PRICE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRICE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.thoughtcrime.securesms.preferences.prosettings.BaseProSettingsScreen
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.GetProPlan
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.SelectProPlan
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowTCPolicyDialog
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.ProPlan
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.ProPlanBadge
import org.thoughtcrime.securesms.pro.ProStatus
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.ui.LoadingArcOr
import org.thoughtcrime.securesms.ui.SpeechBubbleTooltip
import org.thoughtcrime.securesms.ui.components.AccentFillButtonRect
import org.thoughtcrime.securesms.ui.components.RadioButtonIndicator
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.components.iconExternalLink
import org.thoughtcrime.securesms.ui.components.inlineContentMap
import org.thoughtcrime.securesms.ui.components.radioButtonColors
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.sessionDropShadow
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.bold
import org.thoughtcrime.securesms.util.DateUtils


@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ChoosePlan(
    planData: ProSettingsViewModel.ChoosePlanState,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
) {
    BaseProSettingsScreen(
        disabled = false,
        onBack = onBack,
    ) {
        // Keeps track of the badge height dynamically so we can adjust the padding accordingly
        // This is better than a static badge height since users can change their font settings
        // and display sizes for better visibility and the padding should work accordingly
        var badgeHeight by remember { mutableStateOf(0.dp) }

        Spacer(Modifier.height(LocalDimensions.current.spacing))

        val context = LocalContext.current
        val title = when (planData.proStatus) {
            is ProStatus.Active.Expiring -> Phrase.from(context.getText(R.string.proAccessActivatedNotAuto))
                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                .put(DATE_KEY, planData.proStatus.renewingAtFormatted())
                .format()

            is ProStatus.Active.AutoRenewing -> Phrase.from(context.getText(R.string.proAccessActivatesAuto))
                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                .put(
                    CURRENT_PLAN_LENGTH_KEY, DateUtils.getLocalisedTimeDuration(
                        context = context,
                        amount = planData.proStatus.duration.duration.months,
                        unit = MeasureUnit.MONTH
                    )
                )
                .put(DATE_KEY, planData.proStatus.renewingAtFormatted())
                .format()

            else ->
                Phrase.from(context.getText(R.string.proChooseAccess))
                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                    .format()
        }

        Text(
            modifier = Modifier.fillMaxWidth(),
            text = annotatedStringResource(title),
            textAlign = TextAlign.Center,
            style = LocalType.current.base,
            color = LocalColors.current.text,

            )

        Spacer(Modifier.height(LocalDimensions.current.smallSpacing))

        // SUBSCRIPTIONS
        planData.plans.forEachIndexed { index, data ->
            if (index != 0) {
                Spacer(
                    Modifier.height(
                        if (data.badges.isNotEmpty()) {
                            max(
                                LocalDimensions.current.xsSpacing,
                                LocalDimensions.current.contentSpacing - badgeHeight / 2
                            )
                        } else LocalDimensions.current.contentSpacing
                    )
                )
            }
            PlanItem(
                proPlan = data,
                badgePadding = badgeHeight / 2,
                onBadgeLaidOut = { height -> badgeHeight = max(badgeHeight, height) },
                enabled = !planData.purchaseInProgress,
                onClick = {
                    sendCommand(SelectProPlan(data))
                }
            )
        }

        Spacer(Modifier.height(LocalDimensions.current.contentSpacing))

        val buttonLabel = when (planData.proStatus) {
            is ProStatus.Expired -> context.getString(R.string.renew)
            is ProStatus.Active.Expiring -> Phrase.from(LocalContext.current, R.string.updateAccess)
                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                .format().toString()
            is ProStatus.NeverSubscribed -> stringResource(R.string.upgrade)
            else -> Phrase.from(LocalContext.current, R.string.updateAccess)
                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                .format().toString()
        }

        AccentFillButtonRect(
            modifier = Modifier.fillMaxWidth()
                .widthIn(max = LocalDimensions.current.maxContentWidth),
            enabled = planData.enableButton,
            onClick = {
                sendCommand(GetProPlan)
            }
        ){
            LoadingArcOr(loading = planData.purchaseInProgress) {
                Text(text = buttonLabel)
            }
        }

        Spacer(Modifier.height(LocalDimensions.current.xxsSpacing))

        val (footerAction, footerActivation) = when (planData.proStatus) {
            is ProStatus.Expired ->
                stringResource(R.string.proRenewingAction) to
                        stringResource(R.string.proReactivatingActivation)


            is ProStatus.Active -> stringResource(R.string.proUpdatingAction) to ""

            is ProStatus.NeverSubscribed ->
                stringResource(R.string.proUpgradingAction) to
                        stringResource(R.string.proActivatingActivation)

        }

        val footer = Phrase.from(LocalContext.current.getText(R.string.noteTosPrivacyPolicy))
            .put(ACTION_TYPE_KEY, footerAction)
            .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
            .put(ICON_KEY, iconExternalLink)
            .format()

        Text(
            modifier = Modifier.fillMaxWidth()
                .clickable(
                    onClick = {
                        sendCommand(ShowTCPolicyDialog)
                    }
                )
                .padding(
                    horizontal = LocalDimensions.current.spacing,
                    vertical = LocalDimensions.current.xxsSpacing
                )
                .clip(MaterialTheme.shapes.extraSmall),
            text = annotatedStringResource(footer),
            textAlign = TextAlign.Center,
            style = LocalType.current.base,
            color = LocalColors.current.text,
            inlineContent = inlineContentMap(
                textSize = LocalType.current.small.fontSize,
                imageColor = LocalColors.current.text
            ),
        )

        // add another label in cases other than an active subscription
        if (planData.proStatus !is ProStatus.Active) {
            Spacer(Modifier.height(LocalDimensions.current.xxxsSpacing))
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = annotatedStringResource(
                    Phrase.from(LocalContext.current.getText(R.string.proTosDescription))
                        .put(ACTION_TYPE_KEY, footerAction)
                        .put(ACTIVATION_TYPE_KEY, footerActivation)
                        .put(ENTITY_KEY, NonTranslatableStringConstants.ENTITY_STF)
                        .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                        .put(APP_NAME_KEY, NonTranslatableStringConstants.APP_NAME)
                        .format()
                ),
                textAlign = TextAlign.Center,
                style = LocalType.current.base,
                color = LocalColors.current.text,
            )
        }
    }
}

@Composable
private fun PlanItem(
    proPlan: ProPlan,
    badgePadding: Dp,
    enabled: Boolean,
    modifier: Modifier= Modifier,
    onBadgeLaidOut: (Dp) -> Unit,
    onClick: () -> Unit
){
    val density = LocalDensity.current
    val isLight = LocalColors.current.isLight

    // outer box
    Box(modifier = modifier.fillMaxWidth()) {
        // card content
        Box(
            modifier = modifier
                .padding(top = if(proPlan.badges.isNotEmpty()) maxOf(badgePadding, 9.dp) else 0.dp) // 9.dp is a simple fallback to match default styling
                .then(
                    if(isLight) Modifier.sessionDropShadow()
                    else Modifier
                )
                .background(
                    color = LocalColors.current.backgroundSecondary,
                    shape = MaterialTheme.shapes.small
                )
                .border(
                    width = 1.dp,
                    color = if(proPlan.selected) LocalColors.current.accentText else LocalColors.current.borders,
                    shape = MaterialTheme.shapes.small
                )
                .clip(MaterialTheme.shapes.small)
                .then(
                    if (enabled) Modifier.clickable(
                        onClick = onClick
                    )
                    else Modifier
                ),

        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(LocalDimensions.current.smallSpacing)
                    .padding(top = if(proPlan.badges.isNotEmpty()) badgePadding/2 else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = proPlan.title,
                        style = LocalType.current.h7,
                    )

                    Text(
                        text = proPlan.subtitle,
                        style = LocalType.current.small.copy(color = LocalColors.current.textSecondary),
                    )
                }

                RadioButtonIndicator(
                    selected = proPlan.selected,
                    enabled = enabled,
                    colors = radioButtonColors(
                        unselectedBorder = LocalColors.current.borders,
                        selectedBorder = LocalColors.current.accentText,
                    )
                )
            }
        }

        // badges
        if(proPlan.badges.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(start = LocalDimensions.current.smallSpacing)
                    .onGloballyPositioned { coordinates ->
                        onBadgeLaidOut(with(density) { coordinates.size.height.toDp() })
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                proPlan.badges.forEach {
                    PlanBadge(
                        modifier = Modifier.weight(1f, fill = false),
                        badge = it
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanBadge(
    badge: ProPlanBadge,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = LocalColors.current.accent,
        shape = RoundedCornerShape(4.0.dp)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 6.dp,
                vertical = 2.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxxsSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = badge.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = LocalType.current.small.bold().copy(
                    color = LocalColors.current.textOnAccent
                )
            )

            if (badge.tooltip != null) {
                val tooltipState = rememberTooltipState(isPersistent = true)
                val scope = rememberCoroutineScope()

                SpeechBubbleTooltip(
                    text = badge.tooltip,
                    tooltipState = tooltipState
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_circle_help),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(LocalColors.current.textOnAccent),
                        modifier = Modifier
                            .size(LocalDimensions.current.iconXXSmall)
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
}

@Preview
@Composable
private fun PreviewUpdatePlanItems(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(LocalDimensions.current.spacing),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PlanItem(
                proPlan = ProPlan(
                    title = "Plan 1",
                    subtitle = "Subtitle",
                    selected = true,
                    currentPlan = true,
                    durationType = ProSubscriptionDuration.TWELVE_MONTHS,
                    badges = listOf(
                        ProPlanBadge("Current Plan"),
                        ProPlanBadge("20% Off", "This is a tooltip"),
                    ),
                ),
                badgePadding = 0.dp,
                onBadgeLaidOut = {},
                enabled = true,
                onClick = {}
            )

            PlanItem(
                proPlan = ProPlan(
                    title = "Plan 2",
                    subtitle = "Subtitle",
                    selected = false,
                    currentPlan = false,
                    durationType = ProSubscriptionDuration.TWELVE_MONTHS,
                    badges = listOf(
                        ProPlanBadge("Current Plan"),
                        ProPlanBadge("20% Off", "This is a tooltip"),
                    ),
                ),
                badgePadding = 0.dp,
                enabled = true,
                onBadgeLaidOut = {},
                onClick = {}
            )

            PlanItem(
                proPlan = ProPlan(
                    title = "Plan 1 with a very long title boo foo bar hello there",
                    subtitle = "Subtitle that is also very long and is allowed to go onto another line",
                    selected = true,
                    currentPlan = true,
                    durationType = ProSubscriptionDuration.TWELVE_MONTHS,
                    badges = listOf(
                        ProPlanBadge("Current Plan"),
                        ProPlanBadge(
                            "20% Off but that is very long so we can test how this renders to be safe",
                            "This is a tooltip"
                        ),
                    ),
                ),
                badgePadding = 0.dp,
                enabled = true,
                onBadgeLaidOut = {},
                onClick = {}
            )
        }
    }
}

@Preview
@Composable
private fun PreviewUpdatePlan(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        val context = LocalContext.current
        ChoosePlan(
            planData = ProSettingsViewModel.ChoosePlanState(
                enableButton = true,
                plans = listOf(
                    ProPlan(
                        title = Phrase.from(context.getText(R.string.proPriceTwelveMonths))
                            .put(MONTHLY_PRICE_KEY, "$3.99")
                            .format().toString(),
                        subtitle = Phrase.from(context.getText(R.string.proBilledAnnually))
                            .put(PRICE_KEY, "$47.99")
                            .format().toString(),
                        selected = false,
                        currentPlan = false,
                        durationType = ProSubscriptionDuration.TWELVE_MONTHS,
                        badges = listOf(
                            ProPlanBadge("20% Off"),
                        ),
                    ),
                    ProPlan(
                        title = Phrase.from(context.getText(R.string.proPriceThreeMonths))
                            .put(MONTHLY_PRICE_KEY, "$4.99")
                            .format().toString(),
                        subtitle = Phrase.from(context.getText(R.string.proBilledQuarterly))
                            .put(PRICE_KEY, "$14.99")
                            .format().toString(),
                        selected = true,
                        currentPlan = true,
                        durationType = ProSubscriptionDuration.TWELVE_MONTHS,
                        badges = listOf(
                            ProPlanBadge("Current Plan"),
                            ProPlanBadge("20% Off", "This is a tooltip"),
                        ),
                    ),
                    ProPlan(
                        title = Phrase.from(context.getText(R.string.proPriceOneMonth))
                            .put(MONTHLY_PRICE_KEY, "$5.99")
                            .format().toString(),
                        subtitle = Phrase.from(context.getText(R.string.proBilledMonthly))
                            .put(PRICE_KEY, "$5")
                            .format().toString(),
                        selected = false,
                        currentPlan = false,
                        durationType = ProSubscriptionDuration.TWELVE_MONTHS,
                        badges = emptyList(),
                    ),
                )
            ),
            sendCommand = {},
            onBack = {},
        )
    }
}


