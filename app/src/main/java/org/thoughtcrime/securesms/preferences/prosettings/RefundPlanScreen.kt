package org.thoughtcrime.securesms.preferences.prosettings

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import org.session.libsession.utilities.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_KEY
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.pro.ProStatus
import org.thoughtcrime.securesms.pro.ProUrls
import org.thoughtcrime.securesms.pro.getPlatformDisplayName
import org.thoughtcrime.securesms.pro.isFromAnotherPlatform
import org.thoughtcrime.securesms.pro.previewAutoRenewingApple
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.bold


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RefundPlanScreen(
    viewModel: ProSettingsViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) {
        // ensuring we get the latest data here
        // since we can deep link to this screen without going through the pro home screen
        viewModel.ensureRefundState()
    }

    val state by viewModel.refundPlanState.collectAsState()

    BaseStateProScreen(
        state = state,
        onBack = onBack
    ) { refundData ->
        val activePlan = refundData.proStatus

        // there are different UI depending on the state
        when {
            // there is an active subscription but from a different platform or from the same platform
            // but a different account
            //
            // The account half matters as much as the platform half: a refund can only be requested from
            // the account that bought the plan, so offering the originating screen to a different account
            // offers an action it cannot complete. `CancelPlanScreen` and `ChoosePlanHomeScreen` have
            // always made both checks; this screen only made the first.
            activePlan.providerData.isFromAnotherPlatform()
                    || !refundData.hasValidSubscription ->
                RefundPlanNonOriginating(
                    subscription = activePlan,
                    // The window governs this screen too, and it was resolved right here — this branch
                    // simply never passed it on, so one screen served both the <48h and >48h states with
                    // the former's button and the latter's link.
                    isQuickRefund = refundData.isQuickRefund,
                    sendCommand = viewModel::onCommand,
                    onBack = onBack,
                )

            // default refund screen
            else -> RefundPlan(
                data = activePlan,
                isQuickRefund = refundData.isQuickRefund,
                quickRefundUrl = refundData.quickRefundUrl,
                sendCommand = viewModel::onCommand,
                onBack = onBack,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun RefundPlan(
    data: ProStatus.Active.WithPlan,
    isQuickRefund: Boolean,
    quickRefundUrl: String?,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    BaseCellButtonProSettingsScreen(
        screenQaTag = R.string.qa_pro_screen_refund_plan,
        disabled = true,
        onBack = onBack,
        buttonText = if(isQuickRefund) Phrase.from(context.getText(R.string.openPlatformWebsite))
            // `getPlatformDisplayName`, not `platform`: the designs spell the rule out - Apple reads
            // "Apple" but our own store reads "Google Play Store", never "Google". iOS and Desktop
            // both branch the same way; these two refund screens were the only sites still passing
            // the raw platform name.
            .put(PLATFORM_KEY, data.providerData.getPlatformDisplayName())
            .format().toString()
        else stringResource(R.string.requestRefund),
        dangerButton = true,
        onButtonClick = {
            // Two Session-owned links, chosen on the window alone - not the provider's own
            // `refund_platform_url`/`refund_support_url`. Being ours, the destinations can be
            // repointed without a client release, and all three clients agree on them.
            //
            // The window is what decides who can act: while it is open the store takes the request,
            // and once it closes only Session can, which is what this screen's copy promises.
            // The store route's url is libsession's, read through `providerUrls` — it owns the
            // per-provider table and says so. Note the slot: for Google Play its `refund_support_url`
            // IS the Session short link that redirects into the Play store, so the value we want for
            // the window-OPEN route sits under libsession's "support" name. The window-closed route
            // uses `ProUrls.SUPPORT` instead, which mirrors `url_pro_support` — that one has no Kotlin
            // accessor, which is the only reason it is still a copy.
            //
            // No provider gate here: this screen only ever shows a plan bought on this store.
            sendCommand(
                ShowOpenUrlDialog(
                    if (isQuickRefund) data.providerData.refundSupportUrl else ProUrls.SUPPORT
                )
            )
        },
        title = stringResource(R.string.proRefundDescription),
    ){
        Column {
            Text(
                modifier = Modifier.qaTag(R.string.qa_pro_screen_title),
                text = Phrase.from(context.getText(R.string.proRefunding))
                    .format().toString(),
                style = LocalType.current.base.bold(),
                color = LocalColors.current.text,
            )

            Spacer(Modifier.height(LocalDimensions.current.xxxsSpacing))

            Text(
                // The one line that separates the two refund routes this screen offers: inside the store's
                // own window it points at the store, outside it at Session Support.
                modifier = Modifier.qaTag(R.string.qa_pro_screen_description),
                text = annotatedStringResource(
                    if(isQuickRefund)
                        Phrase.from(context.getText(R.string.proRefundRequestStorePolicies))
                            .put(PLATFORM_KEY, data.providerData.platform)
                            .format()
                    else Phrase.from(context.getText(R.string.proRefundRequestSessionSupport))
                        .format()
                ),
                style = LocalType.current.base,
                color = LocalColors.current.text,
            )

            Spacer(Modifier.height(LocalDimensions.current.smallSpacing))

            Text(
                text = stringResource(R.string.important),
                style = LocalType.current.base.bold(),
                color = LocalColors.current.text,
            )

            Spacer(Modifier.height(LocalDimensions.current.xxxsSpacing))

            Text(
                text = annotatedStringResource(
                    Phrase.from(context.getText(R.string.proImportantDescription))
                        .format()
                ),
                style = LocalType.current.base,
                color = LocalColors.current.text,
            )
        }
    }
}

@Preview
@Composable
private fun PreviewRefundPlan(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        RefundPlan(
            data = previewAutoRenewingApple,
            isQuickRefund = false,
            quickRefundUrl = "",
            sendCommand = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PreviewQuickRefundPlan(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        RefundPlan(
            data = previewAutoRenewingApple,
            isQuickRefund = true,
            quickRefundUrl = "",
            sendCommand = {},
            onBack = {},
        )
    }
}


