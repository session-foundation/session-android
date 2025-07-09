package org.thoughtcrime.securesms.contacts

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.util.AsyncLoader


class ShareContactListLoader(
    context: Context,
    val filter: String?,
    private val deprecationManager: LegacyGroupDeprecationManager,
    private val storage: StorageProtocol,
    private val repo: ConversationRepository,
) : AsyncLoader<List<Recipient>>(context) {

    override fun loadInBackground(): List<Recipient> {
        val threads = runBlocking {
            repo.observeConversationList(approved = true)
                .first()
        }
            .asSequence()
            .filter { thread ->
                val recipient = thread.recipient
                if(recipient.isLegacyGroupRecipient && deprecationManager.isDeprecated) return@filter false // ignore legacy group when deprecated
                if(recipient.isCommunityRecipient) { // ignore communities without write access
                    val threadId = storage.getThreadId(recipient.address) ?: return@filter false
                    val openGroup = storage.getOpenGroup(threadId) ?: return@filter false
                    return@filter openGroup.canWrite
                }
                if (filter.isNullOrEmpty()) return@filter true
                recipient.displayName.contains(filter.trim(), true) || recipient.address.toString().contains(filter.trim(), true)
            }
            .toMutableList()

        threads.sortWith(COMPARATOR)

        return threads.map { it.recipient }
    }

    companion object {
        private val COMPARATOR = compareByDescending<ThreadRecord> { it.recipient.isLocalNumber } // NTS come first
            .thenByDescending { it.lastMessage?.timestamp ?: 0L } // then order by last message time
            .thenBy { it.recipient.displayName }
    }
}