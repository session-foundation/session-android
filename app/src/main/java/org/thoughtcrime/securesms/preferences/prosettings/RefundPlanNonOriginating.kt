package org.thoughtcrime.securesms.preferences.prosettings

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import org.session.libsession.utilities.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.DEVICE_TYPE_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_ACCOUNT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_STORE_KEY
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import network.loki.messenger.libsession_util.pro.BackendRequests.PAYMENT_PROVIDER_GOOGLE_PLAY
import org.thoughtcrime.securesms.pro.ProStatus
import org.thoughtcrime.securesms.pro.ProUrls
import org.thoughtcrime.securesms.pro.getPlatformDisplayName
import org.thoughtcrime.securesms.pro.previewAutoRenewingApple
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun RefundPlanNonOriginating(
    subscription: ProStatus.Active.WithPlan,
    /// Whether the store's own quick-refund window is still open. Decides all three of the button, the
    /// body copy and the link, exactly as it does on the originating screen — while the store will take
    /// the request it is sent there, and once it will not, only Session can action it.
    isQuickRefund: Boolean,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
){
    val context = LocalContext.current

    BaseNonOriginatingProSettingsScreen(
        screenQaTag = R.string.qa_pro_screen_refund_plan_non_originating,
        disabled = true,
        onBack = onBack,
        headerTitle = stringResource(R.string.proRefundDescription),
        buttonText = if (isQuickRefund)
            // See RefundPlanScreen: "Google Play Store", not "Google".
            Phrase.from(context.getText(R.string.openPlatformWebsite))
                .put(PLATFORM_KEY, subscription.providerData.getPlatformDisplayName())
                .format().toString()
        else stringResource(R.string.requestRefund),
        dangerButton = true,
        onButtonClick = {
            // Gated on the originating store as well as the window: the quick-refund link is Google
            // Play's and redirects into the Play store, so it is only a usable route for a plan bought
            // there. An App Store plan reports its window open for the whole subscription, so gating on
            // the window alone would send an Apple subscriber to the wrong store's refund flow — which
            // this screen, showing only non-originating plans, is where that would happen.
            val canUseStoreRoute = isQuickRefund &&
                    subscription.providerData.slug == PAYMENT_PROVIDER_GOOGLE_PLAY
            sendCommand(
                ShowOpenUrlDialog(if (canUseStoreRoute) ProUrls.QUICK_REFUND else ProUrls.SUPPORT)
            )
        },
        contentTitle = Phrase.from(context.getText(R.string.proRefunding))
            .format().toString(),
        // Past the window the request is Session's to handle, and the copy has to say so — this is the
        // same sentence the originating screen shows, with the non-originating premise kept.
        contentDescription = if (isQuickRefund)
            Phrase.from(context.getText(R.string.proPlanPlatformRefund))
                .put(PLATFORM_STORE_KEY, subscription.providerData.store)
                .put(PLATFORM_ACCOUNT_KEY, subscription.providerData.platformAccount)
                .format()
        else Phrase.from(context.getText(R.string.proPlanPlatformRefundLong))
            .put(PLATFORM_STORE_KEY, subscription.providerData.store)
            .format(),
        linkCellsInfo = stringResource(R.string.refundRequestOptions),
        linkCells = listOf(
            NonOriginatingLinkCellData(
                title =  Phrase.from(context.getText(R.string.onDevice))
                    .put(DEVICE_TYPE_KEY, subscription.providerData.device)
                    .format(),
                info = Phrase.from(context.getText(R.string.proRefundAccountDevice))
                    .put(DEVICE_TYPE_KEY, subscription.providerData.device)
                    .put(PLATFORM_ACCOUNT_KEY, subscription.providerData.platformAccount)
                    .format(),
                iconRes = R.drawable.ic_smartphone,
                qaTag = R.string.qa_pro_link_cell_device,
                titleQaTag = R.string.qa_pro_link_cell_device_title,
                descriptionQaTag = R.string.qa_pro_link_cell_device_description,
            ),
            NonOriginatingLinkCellData(
                title =  Phrase.from(context.getText(R.string.onPlatformWebsite))
                    .put(PLATFORM_KEY, subscription.providerData.platform)
                    .format(),
                info = Phrase.from(context.getText(R.string.requestRefundPlatformWebsite))
                    .put(PLATFORM_KEY, subscription.providerData.platform)
                    .put(PLATFORM_ACCOUNT_KEY, subscription.providerData.platformAccount)
                    .format(),
                iconRes = R.drawable.ic_globe,
                qaTag = R.string.qa_pro_link_cell_website,
                titleQaTag = R.string.qa_pro_link_cell_website_title,
                descriptionQaTag = R.string.qa_pro_link_cell_website_description,
            )
        )
    )
}

@Preview
@Composable
private fun PreviewUpdatePlan(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        val context = LocalContext.current
        RefundPlanNonOriginating (
            subscription = previewAutoRenewingApple,
            isQuickRefund = true,
            sendCommand = {},
            onBack = {},
        )
    }
}