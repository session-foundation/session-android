package org.thoughtcrime.securesms.conversation.v2.dialogs

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import androidx.fragment.app.DialogFragment
import network.loki.messenger.R
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.thoughtcrime.securesms.createSessionDialog
import org.thoughtcrime.securesms.ui.getSubbedCharSequence

/** Shown upon sending a message to a user that's blocked. */
class BlockedDialog(private val recipient: Address, private val contactName: String) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = createSessionDialog {
        val explanationCS = context.getSubbedCharSequence(R.string.blockUnblockName, NAME_KEY to contactName)
        val spannable = SpannableStringBuilder(explanationCS)
        val startIndex = explanationCS.indexOf(contactName)
        spannable.setSpan(StyleSpan(Typeface.BOLD), startIndex, startIndex + contactName.count(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        title(resources.getString(R.string.blockUnblock))
        text(spannable)
        dangerButton(R.string.blockUnblock, R.string.AccessibilityId_unblockConfirm) { unblock() }
        cancelButton { dismiss() }
    }

    private fun unblock() {
        MessagingModuleConfiguration.shared.storage.setBlocked(listOf(recipient), false)
        dismiss()
    }
}
