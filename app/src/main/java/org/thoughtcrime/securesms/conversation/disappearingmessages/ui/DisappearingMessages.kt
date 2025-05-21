package org.thoughtcrime.securesms.conversation.disappearingmessages.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.thoughtcrime.securesms.ui.BottomFadingEdgeBox
import org.thoughtcrime.securesms.ui.OptionsCard
import org.thoughtcrime.securesms.ui.RadioOption
import org.thoughtcrime.securesms.ui.components.AppBarBackIcon
import org.thoughtcrime.securesms.ui.components.AppBarText
import org.thoughtcrime.securesms.ui.components.PrimaryOutlineButton
import org.thoughtcrime.securesms.ui.components.appBarColors
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType

typealias ExpiryRadioOption = RadioOption<ExpiryMode>

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisappearingMessages(
    state: UiState,
    onOptionSelected: (ExpiryMode) -> Unit,
    onSetClicked: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AppBarText(
                            title = stringResource(R.string.disappearingMessages),
                            singleLine = true
                        )

                        if (state.subtitle?.string()?.isEmpty() == false) {
                            Text(
                                modifier = Modifier.padding(horizontal = LocalDimensions.current.xlargeSpacing),
                                text = state.subtitle.string(),
                                textAlign = TextAlign.Center,
                                color = LocalColors.current.text,
                                style = LocalType.current.extraSmall
                            )
                        }
                    }
                },
                navigationIcon = {
                    AppBarBackIcon(onBack = onBack)
                },
                colors = appBarColors(LocalColors.current.background)
            )
        },
    ) { paddings ->
        Column(
            modifier = Modifier.padding(paddings).consumeWindowInsets(paddings)
        ) {
            BottomFadingEdgeBox(modifier = Modifier.weight(1f)) { bottomContentPadding ->
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = LocalDimensions.current.spacing)
                ) {
                    Spacer(modifier = Modifier.height(LocalDimensions.current.xsSpacing))

                    state.cards.forEachIndexed { index, option ->
                        OptionsCard(option, onOptionSelected)

                        // add spacing if not the last item
                        if (index != state.cards.lastIndex) {
                            Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))
                        }
                    }

                    if (state.showGroupFooter) Text(
                        text = stringResource(R.string.disappearingMessagesDescription) +
                                "\n" +
                                stringResource(R.string.disappearingMessagesOnlyAdmins),
                        style = LocalType.current.extraSmall,
                        fontWeight = FontWeight(400),
                        color = LocalColors.current.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = LocalDimensions.current.xsSpacing)
                    )

                    Spacer(modifier = Modifier.height(bottomContentPadding))
                }
            }

            if (state.showSetButton) {
                PrimaryOutlineButton(
                    stringResource(R.string.set),
                    modifier = Modifier
                        .qaTag(R.string.AccessibilityId_setButton)
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = LocalDimensions.current.spacing),
                    onClick = onSetClicked
                )
            }
        }
    }
}
