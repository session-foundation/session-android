package org.thoughtcrime.securesms.onboarding

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.theme.LocalColors

@Composable
fun OnboardingBackPressAlertDialog(
    dismissDialog: () -> Unit,
    @StringRes textId: Int,
    quit: () -> Unit
) {
    val c = LocalContext.current

    AlertDialog(
        onDismissRequest = dismissDialog,
        title = stringResource(R.string.warning),
        text = stringResource(textId).let { txt ->
            Phrase.from(txt).put(APP_NAME_KEY, c.getString(R.string.app_name)).format().toString()
        },
        buttons = listOf(
            DialogButtonData(
                text = GetString(stringResource(id = R.string.quitButton)),
                color = LocalColors.current.danger,
                onClick = quit
            ),
            DialogButtonData(
                GetString(stringResource(R.string.cancel))
            )
        )
    )
}
