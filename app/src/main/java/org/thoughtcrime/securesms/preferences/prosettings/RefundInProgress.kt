package org.thoughtcrime.securesms.preferences.prosettings

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.clickable
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
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.ICON_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PLATFORM_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.ShowOpenUrlDialog
import org.thoughtcrime.securesms.pro.ProStatus
import org.thoughtcrime.securesms.pro.getPlatformDisplayName
import org.thoughtcrime.securesms.pro.previewAutoRenewingApple
import org.thoughtcrime.securesms.ui.components.annotatedStringResource
import org.thoughtcrime.securesms.ui.components.iconExternalLink
import org.thoughtcrime.securesms.ui.components.inlineContentMap
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors
import org.thoughtcrime.securesms.ui.theme.bold


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RefundInProgressScreen(
    viewModel: ProSettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.proSettingsUIState.collectAsState()
    val activePlan = state.proDataState.type as? ProStatus.Active ?: return
    
    RefundInProgress(
        subscription = activePlan,
        sendCommand = viewModel::onCommand,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun RefundInProgress(
    subscription: ProStatus.Active,
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
){
    val context = LocalContext.current

    BaseCellButtonProSettingsScreen(
        disabled = true,
        onBack = onBack,
        buttonText = stringResource(R.string.theReturn),
        dangerButton = false,
        onButtonClick = onBack,
        title = stringResource(R.string.proRequestedRefund),
    ){
        Column {
            Text(
                text = stringResource(R.string.nextSteps),
                style = LocalType.current.base.bold(),
                color = LocalColors.current.text,
            )

            Spacer(Modifier.height(LocalDimensions.current.xxxsSpacing))

            Text(
                text = annotatedStringResource(
                    Phrase.from(context.getText(R.string.proRefundNextSteps))
                        .put(PLATFORM_KEY, subscription.providerData.getPlatformDisplayName())
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .put(APP_NAME_KEY, context.getString(R.string.app_name))
                        .format()
                ),
                style = LocalType.current.base,
                color = LocalColors.current.text,
            )

            Spacer(Modifier.height(LocalDimensions.current.smallSpacing))

            Text(
                text = stringResource(R.string.helpSupport),
                style = LocalType.current.base.bold(),
                color = LocalColors.current.text,
            )

            Spacer(Modifier.height(LocalDimensions.current.xxxsSpacing))

            Text(
                modifier = Modifier.clickable(
                        onClick = {
                            sendCommand(ShowOpenUrlDialog(subscription.providerData.refundStatusUrl))
                        }
                    ),
                text = annotatedStringResource(
                    Phrase.from(context.getText(R.string.proRefundSupport))
                        .put(PLATFORM_KEY, subscription.providerData.getPlatformDisplayName())
                        .put(APP_NAME_KEY, context.getString(R.string.app_name))
                        .put(ICON_KEY, iconExternalLink)
                        .format()
                ),
                style = LocalType.current.base,
                color = LocalColors.current.text,
                inlineContent = inlineContentMap(
                    textSize = LocalType.current.small.fontSize,
                    imageColor = LocalColors.current.accentText
                )
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
        RefundInProgress (
            subscription = previewAutoRenewingApple,
            sendCommand = {},
            onBack = {},
        )
    }
}