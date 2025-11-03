package org.thoughtcrime.securesms.preferences.prosettings

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.NonTranslatableStringConstants.NETWORK_NAME
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.DATE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NETWORK_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.session.libsession.utilities.recipients.ProStatus
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.GoToProSettings
import org.thoughtcrime.securesms.pro.SubscriptionDetails
import org.thoughtcrime.securesms.pro.SubscriptionState
import org.thoughtcrime.securesms.pro.SubscriptionType
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.ui.SessionProSettingsHeader
import org.thoughtcrime.securesms.ui.components.AccentFillButtonRect
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.safeContentWidth
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.util.State
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
    proData: ProSettingsViewModel.ProSettingsState,
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

            val description = when (proData.subscriptionState.type) {
                is SubscriptionType.Active -> {
                    Phrase.from(context.getText(R.string.proAllSetDescription))
                        .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .put(DATE_KEY, proData.subscriptionExpiryDate)
                        .format()
                }

                is SubscriptionType.NeverSubscribed -> {
                    Phrase.from(context.getText(R.string.proUpgraded))
                        .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                        .put(NETWORK_NAME_KEY, NETWORK_NAME)
                        .format()
                }

                is SubscriptionType.Expired -> {
                    Phrase.from(context.getText(R.string.proPlanRenewSupport))
                        .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                        .put(NETWORK_NAME_KEY, NETWORK_NAME)
                        .format()
                }
            }

            Text(
                modifier = Modifier.align(CenterHorizontally)
                    .safeContentWidth(),
                text = annotatedStringResource(description),
                textAlign = TextAlign.Center,
                style = LocalType.current.base,
                color = LocalColors.current.text,
            )

            Spacer(Modifier.height(LocalDimensions.current.spacing))

            val buttonLabel = when (proData.subscriptionState.type) {
                is SubscriptionType.Active -> stringResource(R.string.theReturn)

                else -> {
                    Phrase.from(context.getText(R.string.proStartUsing))
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .format()
                        .toString()
                }
            }

            AccentFillButtonRect(
                modifier = Modifier.fillMaxWidth()
                    .widthIn(max = LocalDimensions.current.maxContentWidth),
                text = buttonLabel,
                onClick = {
                    sendCommand(GoToProSettings)
                }
            )

            Spacer(Modifier.weight(1f))
        }
    }
}


@Preview
@Composable
private fun PreviewPlanConfirmationActive(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        PlanConfirmation(
            proData = ProSettingsViewModel.ProSettingsState(
                subscriptionExpiryDate = "20th June 2026",
                subscriptionState = SubscriptionState(
                    type = SubscriptionType.Active.AutoRenewing(
                        proStatus = ProStatus.Pro(
                            visible = true,
                            validUntil = Instant.now() + Duration.ofDays(14),
                        ),
                        duration = ProSubscriptionDuration.THREE_MONTHS,
                        subscriptionDetails = SubscriptionDetails(
                            device = "iOS",
                            store = "Apple App Store",
                            platform = "Apple",
                            platformAccount = "Apple Account",
                            subscriptionUrl = "https://www.apple.com/account/subscriptions",
                            refundUrl = "https://www.apple.com/account/subscriptions",
                        )
                    ),
                    refreshState = State.Success(Unit),),
            ),
            sendCommand = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PreviewPlanConfirmationExpired(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        PlanConfirmation(
            proData = ProSettingsViewModel.ProSettingsState(
                subscriptionState = SubscriptionState(
                    type = SubscriptionType.Expired(
                        expiredAt = Instant.now() - Duration.ofDays(14),
                        SubscriptionDetails(
                            device = "iOS",
                            store = "Apple App Store",
                            platform = "Apple",
                            platformAccount = "Apple Account",
                            subscriptionUrl = "https://www.apple.com/account/subscriptions",
                            refundUrl = "https://www.apple.com/account/subscriptions",
                        )),
                    refreshState = State.Success(Unit),),
            ),
            sendCommand = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PreviewPlanConfirmationNeverSub(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        PlanConfirmation(
            proData = ProSettingsViewModel.ProSettingsState(
                subscriptionState = SubscriptionState(
                    type = SubscriptionType.NeverSubscribed,
                    refreshState = State.Success(Unit),),
            ),
            sendCommand = {},
            onBack = {},
        )
    }
}




