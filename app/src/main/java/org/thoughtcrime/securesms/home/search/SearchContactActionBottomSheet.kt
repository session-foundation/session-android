package org.thoughtcrime.securesms.home.search

import android.content.Context
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
class SearchContactActionBottomSheet : BottomSheetDialogFragment() {

    private var accountId: String? = null
    private var contactName: String? = null

    interface Callbacks {
        fun onBlockContact(accountId: String)
        fun onDeleteContact(accountId: String)
    }

    private var callbacks: Callbacks? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accountId = arguments?.getString(ARG_ACCOUNT_ID)
        contactName = arguments?.getString(ARG_CONTACT_NAME)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
            ?: parentFragment as? Callbacks
                    ?: throw IllegalStateException("Parent activity or fragment must implement Callbacks")
    }

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
        val accountId = accountId ?: return
        val contactName = contactName ?: return

        showSessionDialog {
            title(R.string.block)
            text(
                Phrase.from(context, R.string.blockDescription)
                    .put(NAME_KEY, contactName)
                    .format())
            dangerButton(R.string.block, R.string.AccessibilityId_blockConfirm) {
                callbacks?.onBlockContact(accountId)
                callbacks = null
            }
            cancelButton()
        }
    }

    private fun showDeleteConfirmation() {
        val accountId = accountId ?: return
        val contactName = contactName ?: return

        showSessionDialog {
            title(R.string.contactDelete)
            text(
                Phrase.from(context, R.string.deleteContactDescription)
                    .put(NAME_KEY, contactName)
                    .put(NAME_KEY, contactName)
                    .format())
            dangerButton(R.string.delete, R.string.AccessibilityId_delete) {
                callbacks?.onDeleteContact(accountId)
                callbacks = null
            }
            cancelButton()
        }
    }

    companion object {
        private const val ARG_ACCOUNT_ID = "arg_account_id"
        private const val ARG_CONTACT_NAME = "arg_contact_name"

        fun newInstance(accountId: String, contactName: String): SearchContactActionBottomSheet {
            return SearchContactActionBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ACCOUNT_ID, accountId)
                    putString(ARG_CONTACT_NAME, contactName)
                }
            }
        }
    }
}