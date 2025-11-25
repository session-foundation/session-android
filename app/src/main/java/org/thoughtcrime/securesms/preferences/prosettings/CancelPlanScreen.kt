package org.thoughtcrime.securesms.preferences.prosettings

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import org.session.libsession.utilities.StringSubstitutionConstants.APP_PRO_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.PRO_KEY
import org.thoughtcrime.securesms.preferences.prosettings.ProSettingsViewModel.Commands.OpenCancelSubscriptionPage
import org.thoughtcrime.securesms.pro.isFromAnotherPlatform
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
fun CancelPlanScreen(
    viewModel: ProSettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.cancelPlanState.collectAsState()

    BaseStateProScreen(
        state = state,
        onBack = onBack
    ){ planData ->
        val activePlan = planData.proStatus

        // there are different UI depending on the state
        when {
            // there is an active subscription but from a different platform or from the
            // same platform but a different account
            activePlan.providerData.isFromAnotherPlatform()
                    || !planData.hasValidSubscription ->
                CancelPlanNonOriginating(
                    providerData = activePlan.providerData,
                    sendCommand = viewModel::onCommand,
                    onBack = onBack,
                )

            // default cancel screen
            else -> CancelPlan(
                sendCommand = viewModel::onCommand,
                onBack = onBack,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun CancelPlan(
    sendCommand: (ProSettingsViewModel.Commands) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    BaseCellButtonProSettingsScreen(
        disabled = true,
        onBack = onBack,
        buttonText = Phrase.from(context.getText(R.string.cancelProPlan))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format().toString(),
        dangerButton = true,
        onButtonClick = {
            sendCommand(OpenCancelSubscriptionPage)
        },
        title = Phrase.from(context.getText(R.string.proCancelSorry))
            .put(PRO_KEY, NonTranslatableStringConstants.PRO)
            .format().toString(),
    ){
        Column {
            Text(
                text = stringResource(R.string.proCancellation),
                style = LocalType.current.base.bold(),
                color = LocalColors.current.text,
            )

            Spacer(Modifier.height(LocalDimensions.current.xxxsSpacing))

            Text(
                text = annotatedStringResource(
                    Phrase.from(context.getText(R.string.proCancellationShortDescription))
                        .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .put(PRO_KEY, NonTranslatableStringConstants.PRO)
                        .put(APP_PRO_KEY, NonTranslatableStringConstants.APP_PRO)
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
private fun PreviewCancelPlan(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors
) {
    PreviewTheme(colors) {
        CancelPlan(
            sendCommand = {},
            onBack = {},
        )
    }
}


