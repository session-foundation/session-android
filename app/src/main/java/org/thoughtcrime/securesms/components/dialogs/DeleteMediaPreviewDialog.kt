package org.thoughtcrime.securesms.components.dialogs

import android.content.Context
import network.loki.messenger.R
import org.thoughtcrime.securesms.showSessionDialog

class DeleteMediaPreviewDialog {
    companion object {
        @JvmStatic
        fun show(context: Context, doDelete: Runnable) {
            context.showSessionDialog {
                title(context.resources.getString(R.string.delete))
                text(R.string.deleteMessageDeviceOnly)
                dangerButton(R.string.delete) { doDelete.run() }
                cancelButton()
            }
        }
    }
}