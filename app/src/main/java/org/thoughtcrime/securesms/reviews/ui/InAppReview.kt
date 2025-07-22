package org.thoughtcrime.securesms.reviews.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.AlertDialogContent
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.OpenURLAlertDialog
import org.thoughtcrime.securesms.ui.theme.LocalColors

@Composable
fun InAppReview(
    uiState: InAppReviewViewModel.UiState,
    sendCommands: (InAppReviewViewModel.UiCommand) -> Unit,
) {
    val context = LocalContext.current

    AnimatedContent(uiState) { st ->
        when (st) {
            is InAppReviewViewModel.UiState.Visible -> {
                AlertDialogContent(
                    showCloseButton = true,
                    onDismissRequest = { sendCommands(InAppReviewViewModel.UiCommand.CloseButtonClicked) },
                    title = AnnotatedString(st.title.format(context)),
                    text = AnnotatedString(st.message.format(context)),
                    buttons = listOf(
                        DialogButtonData(
                            text = GetString.FromString(st.positiveButtonText.format(context)),
                            color = LocalColors.current.accent,
                            dismissOnClick = false
                        ) {
                            sendCommands(InAppReviewViewModel.UiCommand.PositiveButtonClicked)
                        },

                        DialogButtonData(
                            text = GetString.FromString(st.negativeButtonText.format(context)),
                            dismissOnClick = false
                        ) {
                            sendCommands(InAppReviewViewModel.UiCommand.NegativeButtonClicked)
                        },
                    )
                )
            }

            InAppReviewViewModel.UiState.Hidden -> {}
            is InAppReviewViewModel.UiState.OpenURLDialog -> OpenURLAlertDialog(
                onDismissRequest = { sendCommands(InAppReviewViewModel.UiCommand.CloseButtonClicked) },
                url = st.url
            )
            InAppReviewViewModel.UiState.ReviewLimitReachedDialog -> AlertDialog(
                onDismissRequest = { sendCommands(InAppReviewViewModel.UiCommand.CloseButtonClicked) },
                showCloseButton = true,
                title = "Review Limit",
                text = "It looks like you've already reviewed Session recently, thanks for your feedback!"
            )
        }
    }
}
