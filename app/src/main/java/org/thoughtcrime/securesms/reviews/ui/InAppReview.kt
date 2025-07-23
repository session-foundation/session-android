package org.thoughtcrime.securesms.reviews.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import com.squareup.phrase.Phrase
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.AlertDialogContent
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.OpenURLAlertDialog
import org.thoughtcrime.securesms.ui.theme.LocalColors
import network.loki.messenger.R
import org.session.libsession.utilities.NonTranslatableStringConstants.SESSION_FEEDBACK_URL
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.EMOJI_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.STORE_VARIANT_KEY
import org.thoughtcrime.securesms.reviews.StoreReviewManager

@Composable
fun InAppReview(
    uiState: InAppReviewViewModel.UiState,
    storeReviewManager: StoreReviewManager,
    sendCommands: (InAppReviewViewModel.UiCommand) -> Unit,
) {
    val context = LocalContext.current

    AnimatedContent(uiState) { st ->
        when (st) {
            InAppReviewViewModel.UiState.StartPrompt -> {
                InAppReviewStartPrompt(sendCommands)
            }

            InAppReviewViewModel.UiState.ConfirmOpeningSurvey -> OpenURLAlertDialog(
                onDismissRequest = { sendCommands(InAppReviewViewModel.UiCommand.CloseButtonClicked) },
                url = SESSION_FEEDBACK_URL,
            )
            InAppReviewViewModel.UiState.ReviewLimitReached -> AlertDialog(
                onDismissRequest = { sendCommands(InAppReviewViewModel.UiCommand.CloseButtonClicked) },
                showCloseButton = true,
                title = context.getString(R.string.reviewLimit),
                text = Phrase.from(context, R.string.reviewLimitDescription)
                    .put(APP_NAME_KEY, context.getString(R.string.app_name))
                    .format()
                    .toString(),
            )

            InAppReviewViewModel.UiState.PositivePrompt -> InAppReviewPositivePrompt(
                storeReviewManager = storeReviewManager,
                sendCommands = sendCommands
            )
            InAppReviewViewModel.UiState.NegativePrompt -> InAppReviewNegativePrompt(sendCommands)
            InAppReviewViewModel.UiState.Hidden -> {}
        }
    }
}

@Composable
private fun InAppReviewDialog(
    title: String,
    message: String,
    positiveButtonText: String,
    negativeButtonText: String,
    sendCommands: (InAppReviewViewModel.UiCommand) -> Unit,
) {
    AlertDialogContent(
        showCloseButton = true,
        onDismissRequest = { sendCommands(InAppReviewViewModel.UiCommand.CloseButtonClicked) },
        title = AnnotatedString(title),
        text = AnnotatedString(message),
        buttons = listOf(
            DialogButtonData(
                text = GetString.FromString(positiveButtonText),
                color = LocalColors.current.accent,
                dismissOnClick = false
            ) {
                sendCommands(InAppReviewViewModel.UiCommand.PositiveButtonClicked)
            },

            DialogButtonData(
                text = GetString.FromString(negativeButtonText),
                dismissOnClick = false
            ) {
                sendCommands(InAppReviewViewModel.UiCommand.NegativeButtonClicked)
            },
        )
    )
}

@Composable
@Preview
private fun InAppReviewStartPrompt(
    sendCommands: (InAppReviewViewModel.UiCommand) -> Unit = {}
) {
    val context = LocalContext.current

    InAppReviewDialog(
        title = Phrase.from(context, R.string.enjoyingSession)
            .put(APP_NAME_KEY, context.getString(R.string.app_name))
            .format()
            .toString(),
        message = Phrase.from(context, R.string.enjoyingSessionDescription)
            .put(APP_NAME_KEY, context.getString(R.string.app_name))
            .format()
            .toString(),
        positiveButtonText = Phrase.from(context, R.string.enjoyingSessionButtonPositive)
            .put(EMOJI_KEY, "❤\uFE0F")
            .format()
            .toString(),
        negativeButtonText = Phrase.from(context, R.string.enjoyingSessionButtonNegative)
            .put(EMOJI_KEY, "\uD83D\uDE15")
            .format()
            .toString(),
        sendCommands = sendCommands
    )
}

@Composable
@Preview
private fun InAppReviewPositivePrompt(
    storeReviewManager: StoreReviewManager? = null,
    sendCommands: (InAppReviewViewModel.UiCommand) -> Unit = {}
) {
    val context = LocalContext.current

    InAppReviewDialog(
        title = Phrase.from(context, R.string.rateSession)
            .put(APP_NAME_KEY, context.getString(R.string.app_name))
            .format()
            .toString(),
        message = Phrase.from(context, R.string.rateSessionModalDescription)
            .put(APP_NAME_KEY, context.getString(R.string.app_name))
            .put(STORE_VARIANT_KEY, storeReviewManager?.storeName ?: "Google Play Store")
            .format()
            .toString(),
        positiveButtonText = context.getString(R.string.rateSessionApp),
        negativeButtonText = context.getString(R.string.notNow),
        sendCommands = sendCommands
    )
}

@Composable
@Preview
private fun InAppReviewNegativePrompt(
    sendCommands: (InAppReviewViewModel.UiCommand) -> Unit = {}
) {
    val context = LocalContext.current

    InAppReviewDialog(
        title = context.getString(R.string.giveFeedback),
        message = Phrase.from(context, R.string.giveFeedbackDescription)
            .put(APP_NAME_KEY, context.getString(R.string.app_name))
            .format()
            .toString(),
        positiveButtonText = context.getString(R.string.openSurvey),
        negativeButtonText = context.getString(R.string.notNow),
        sendCommands = sendCommands
    )
}