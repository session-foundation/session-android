package org.thoughtcrime.securesms.sskenvironment

import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ReadReceiptManagerProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.preferences.CommunicationPreferences
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadReceiptManager @Inject constructor(
    private val prefs: PreferenceStorage,
    private val mmsSmsDatabase: MmsSmsDatabase,
): ReadReceiptManagerProtocol {

    override fun processReadReceipts(
        fromRecipientId: String,
        sentTimestamps: List<Long>,
        readTimestamp: Long
    ) {
        if (prefs[CommunicationPreferences.READ_RECEIPT_ENABLED]) {
            // Redirect message to master device conversation
            val address = Address.fromSerialized(fromRecipientId)
            for (timestamp in sentTimestamps) {
                Log.i(TAG, "Received encrypted read receipt: (XXXXX, $timestamp)")
                mmsSmsDatabase.incrementReadReceiptCount(SyncMessageId(address, timestamp), readTimestamp)
            }
        }
    }

    companion object {
        private const val TAG = "ReadReceiptManager"
    }
}