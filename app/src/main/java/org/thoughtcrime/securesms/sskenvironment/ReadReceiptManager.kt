package org.thoughtcrime.securesms.sskenvironment

import android.content.Context
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId
import org.thoughtcrime.securesms.dependencies.DatabaseComponent

class ReadReceiptManager: SSKEnvironment.ReadReceiptManagerProtocol {

    override fun processReadReceipts(context: Context, fromRecipientId: String, sentTimestamps: List<Long>, readTimestamp: Long) {
        if (TextSecurePreferences.isReadReceiptsEnabled(context)) {

            // Redirect message to master device conversation
            var address = Address.fromSerialized(fromRecipientId)
            for (timestamp in sentTimestamps) {
                Log.i("Loki", "Received encrypted read receipt: (XXXXX, $timestamp)")
                DatabaseComponent.get(context).mmsSmsDatabase().incrementReadReceiptCount(SyncMessageId(address, timestamp), readTimestamp)
            }
        }
    }
}