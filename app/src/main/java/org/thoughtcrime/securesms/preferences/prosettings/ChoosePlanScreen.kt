package org.thoughtcrime.securesms.preferences.prosettings

import android.icu.util.MeasureUnit
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.CURRENT_PLAN_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.DATE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.ICON_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.MONTHLY_PRICE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRICE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.ProPlan
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.ProPlanBadge
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.*
import org.thoughtcrime.securesms.pro.SubscriptionState
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.pro.subscription.expiryFromNow
import org.thoughtcrime.securesms.ui.SpeechBubbleTooltip
import org.thoughtcrime.securesms.ui.components.AccentFillButtonRect
import org.thoughtcrime.securesms.ui.components.RadioButtonIndicator
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.components.iconExternalLink
import org.thoughtcrime.securesms.ui.components.inlineContentMap
import org.thoughtcrime.securesms.ui.components.radioButtonColors
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.bold
import org.thoughtcrime.securesms.util.DateUtils


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ChoosePlanScreen(
    viewModel: ProSettingsViewModel,
    onBack: () -> Unit,
) {
    val planData by viewModel.choosePlanState.collectAsState()

    // there are different UI depending on the state
    when {
       // there is an active subscription but from a different platform
        (planData.subscriptionState as? SubscriptionState.Active)?.nonOriginatingSubscription != null ->
            ChoosePlanNonOriginating(
                subscription = planData.subscriptionState as SubscriptionState.Active,
                sendCommand = viewModel::onCommand,
                onBack = onBack,
            )

        //todo PRO handle the case here when there are no SubscriptionManager available (for example fdroid builds)

        // default plan chooser
        else -> ChoosePlan(
            planData = planData,
            sendCommand = viewModel::onCommand,
            onBack = onBack,
        )
    }
}

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
        val title = when(planData.subscriptionState) {
            is SubscriptionState.Expired ->
                Phrase.from(context.getText(R.string.proPlanRenewStart))
                    .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                    .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                    .format()

            is SubscriptionState.Active.Expiring -> Phrase.from(context.getText(R.string.proPlanActivatedNotAuto))
                .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                .put(DATE_KEY, planData.subscriptionState.type.expiryFromNow())
                .format()

            is SubscriptionState.Active.AutoRenewing -> Phrase.from(context.getText(R.string.proPlanActivatedAuto))
                .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                .put(CURRENT_PLAN_KEY, DateUtils.getLocalisedTimeDuration(
                    context = context,
                    amount = planData.subscriptionState.type.duration.months,
                    unit = MeasureUnit.MONTH
                ))
                .put(DATE_KEY, planData.subscriptionState.type.expiryFromNow())
                .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                .format()

            else -> ""
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
            if(index != 0){
                Spacer(Modifier.height(if(data.badges.isNotEmpty()){
                    max(LocalDimensions.current.xsSpacing, LocalDimensions.current.contentSpacing - badgeHeight/2)
                } else LocalDimensions.current.contentSpacing))
            }
            PlanItem(
                proPlan = data,
                badgePadding = badgeHeight / 2,
                onBadgeLaidOut = { height -> badgeHeight = max(badgeHeight, height) },
                onClick = {
                    sendCommand(SelectProPlan(data))
                }
            )
        }

        Spacer(Modifier.height(LocalDimensions.current.contentSpacing))

        val buttonLabel = when(planData.subscriptionState) {
            is SubscriptionState.Expired -> context.getString(R.string.renew)
            is SubscriptionState.Active.Expiring -> context.getString(R.string.updatePlan)
            else -> context.getString(R.string.updatePlan)
        }

        AccentFillButtonRect(
            modifier = Modifier.fillMaxWidth()
                .widthIn(max = LocalDimensions.current.maxContentWidth),
            text = buttonLabel,
            enabled = planData.enableButton,
            onClick = {
                sendCommand(GetProPlan)
            }
        )

        Spacer(Modifier.height(LocalDimensions.current.xxsSpacing))

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
            text = annotatedStringResource(
                Phrase.from(LocalContext.current.getText(R.string.proTosPrivacy))
                    .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                    .put(ICON_KEY, iconExternalLink)
                    .put(ICON_KEY, iconExternalLink)
                    .format()
            ),
            textAlign = TextAlign.Center,
            style = LocalType.current.small,
            color = LocalColors.current.text,
            inlineContent = inlineContentMap(
                textSize = LocalType.current.small.fontSize,
                imageColor = LocalColors.current.text
            ),
        )
    }
}

@Composable
private fun PlanItem(
    proPlan: ProPlan,
    badgePadding: Dp,
    modifier: Modifier= Modifier,
    onBadgeLaidOut: (Dp) -> Unit,
    onClick: () -> Unit
){
    val density = LocalDensity.current

    // outer box
    Box(modifier = modifier.fillMaxWidth()) {
        // card content
        Box(
            modifier = modifier
                .padding(top = if(proPlan.badges.isNotEmpty()) maxOf(badgePadding, 9.dp) else 0.dp) // 9.dp is a simple fallback to match default styling
                .background(
                    color = LocalColors.current.backgroundSecondary,
                    shape = MaterialTheme.shapes.small
                )
                .border(
                    width = 1.dp,
                    color = if(proPlan.selected) LocalColors.current.accent else LocalColors.current.borders,
                    shape = MaterialTheme.shapes.small
                )
                .clip(MaterialTheme.shapes.small)
                .clickable(
                    onClick = onClick
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(LocalDimensions.current.smallSpacing)
                    .padding(top = if(proPlan.badges.isNotEmpty()) badgePadding/2 else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f)
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
                    enabled = true,
                    colors = radioButtonColors(
                        unselectedBorder = LocalColors.current.borders,
                        selectedBorder = LocalColors.current.accent,
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
                    color = LocalColors.current.accentButtonFillText
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
                        colorFilter = ColorFilter.tint(LocalColors.current.accentButtonFillText),
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
                onBadgeLaidOut = {},
                onClick = {}
            )

            PlanItem(
                proPlan = ProSettingsViewModel.ProPlan(
                    title = "Plan 1 with a very long title boo foo bar hello there",
                    subtitle = "Subtitle that is also very long and is allowed to go onto another line",
                    selected = true,
                    currentPlan = true,
                    durationType = ProSubscriptionDuration.TWELVE_MONTHS,
                    badges = listOf(
                        ProSettingsViewModel.ProPlanBadge("Current Plan"),
                        ProSettingsViewModel.ProPlanBadge(
                            "20% Off but that is very long so we can test how this renders to be safe",
                            "This is a tooltip"
                        ),
                    ),
                ),
                badgePadding = 0.dp,
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


