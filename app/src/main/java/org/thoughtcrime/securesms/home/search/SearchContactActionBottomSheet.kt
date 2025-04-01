package org.thoughtcrime.securesms.home.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.thoughtcrime.securesms.showSessionDialog
import org.thoughtcrime.securesms.ui.components.ActionSheetItem
import org.thoughtcrime.securesms.ui.createThemedComposeView

@AndroidEntryPoint
class SearchContactActionBottomSheet(
    private val accountId: String,
    private val contactName: String,
    private val blockContact: (String) -> Unit,
    private val deleteContact: (String) -> Unit,

    ): BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = createThemedComposeView {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            ActionSheetItem(
                text = stringResource(R.string.block),
                leadingIcon = R.drawable.ic_ban,
                qaTag = stringResource(R.string.AccessibilityId_block),
                onClick = {
                    showBlockConfirmation()
                    dismiss()
                }
            )

            ActionSheetItem(
                text = stringResource(R.string.contactDelete),
                leadingIcon = R.drawable.ic_trash_2,
                qaTag = stringResource(R.string.AccessibilityId_delete),
                onClick = {
                    showDeleteConfirmation()
                    dismiss()
                }
            )
        }
    }

    private fun showBlockConfirmation() {
        showSessionDialog {
            title(R.string.block)
            text(
                Phrase.from(context, R.string.blockDescription)
                .put(NAME_KEY, contactName)
                .format())
            dangerButton(R.string.block, R.string.AccessibilityId_blockConfirm) {
                blockContact(accountId)
            }
            cancelButton()
        }
    }

    private fun showDeleteConfirmation() {
        showSessionDialog {
            title(R.string.contactDelete)
            text(
                Phrase.from(context, R.string.contactDeleteDescription)
                    .put(NAME_KEY, contactName)
                    .put(NAME_KEY, contactName)
                    .format())
            dangerButton(R.string.delete, R.string.AccessibilityId_delete) {
                deleteContact(accountId)
            }
            cancelButton()
        }
    }

}