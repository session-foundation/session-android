package org.thoughtcrime.securesms.preferences.prosettings

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.CURRENT_PLAN_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.DATE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.ICON_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.MONTHLY_PRICE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRICE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.SELECTED_PLAN_KEY
import org.session.libsession.utilities.recipients.ProStatus
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.ProPlan
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.ProPlanBadge
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.*
import org.thoughtcrime.securesms.pro.SubscriptionState
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.ui.SessionProSettingsHeader
import org.thoughtcrime.securesms.ui.SpeechBubbleTooltip
import org.thoughtcrime.securesms.ui.components.AccentFillButtonRect
import org.thoughtcrime.securesms.ui.components.BackAppBar
import org.thoughtcrime.securesms.ui.components.DangerFillButtonRect
import org.thoughtcrime.securesms.ui.components.RadioButtonIndicator
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.components.iconExternalLink
import org.thoughtcrime.securesms.ui.components.inlineContentMap
import org.thoughtcrime.securesms.ui.components.radioButtonColors
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.safeContentWidth
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.bold
import java.time.Duration
import java.time.Instant


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlanConfirmationScreen(
    viewModel: ProSettingsViewModel,
    onBack: () -> Unit,
) {
    val proData by viewModel.proSettingsUIState.collectAsState()

    PlanConfirmation(
        proData = proData,
        sendCommand = viewModel::onCommand,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PlanConfirmation(
    proData: ProSettingsViewModel.ProSettingsUIState,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {},
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
    ) { paddings ->
        val context = LocalContext.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(paddings)
                .padding(
                    horizontal = LocalDimensions.current.spacing,
                )
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))

            SessionProSettingsHeader(
                disabled = false,
            )

            Spacer(Modifier.height(LocalDimensions.current.spacing))

            Text(
                modifier = Modifier.align(CenterHorizontally),
                text = stringResource(R.string.proAllSet),
                style = LocalType.current.h6,
                color = LocalColors.current.text,
            )

            Spacer(Modifier.height(LocalDimensions.current.xsSpacing))

            Text(
                modifier = Modifier.align(CenterHorizontally)
                    .safeContentWidth(),
                //todo PRO the text below can change if the user was renewing vs expiring and/or/auto-renew
                text = annotatedStringResource(
                    Phrase.from(context.getText(R.string.proAllSetDescription))
                    .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                    .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                    .put(DATE_KEY, proData.subscriptionExpiryDate)
                    .format()
                ),
                textAlign = TextAlign.Center,
                style = LocalType.current.base,
                color = LocalColors.current.text,
            )

            Spacer(Modifier.height(LocalDimensions.current.smallSpacing))

            //todo PRO the button text can change if the user was renewing vs expiring and/or/auto-renew
            AccentFillButtonRect(
                modifier = Modifier.fillMaxWidth()
                    .widthIn(max = LocalDimensions.current.maxContentWidth),
                text = stringResource(R.string.theReturn),
                onClick = {}
            )

            Spacer(Modifier.weight(1f))
        }
    }
}


@Preview
@Composable
private fun PreviewPlanConfirmation(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        PlanConfirmation(
            proData = ProSettingsViewModel.ProSettingsUIState(
                subscriptionState = SubscriptionState.Active.AutoRenewing(
                    proStatus = ProStatus.Pro(
                        visible = true,
                        validUntil = Instant.now() + Duration.ofDays(14),
                    ),
                    type = ProSubscriptionDuration.THREE_MONTHS
                ),
            ),
            sendCommand = {},
            onBack = {},
        )
    }
}


