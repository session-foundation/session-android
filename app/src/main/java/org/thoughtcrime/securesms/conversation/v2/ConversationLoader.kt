package org.thoughtcrime.securesms.conversation.v2

import android.content.Context
import android.database.Cursor
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.util.AbstractCursorLoader

class ConversationLoader(
    private val threadID: Long,
    private val reverse: Boolean,
    context: Context,
    val mmsSmsDatabase: MmsSmsDatabase
) : AbstractCursorLoader(context) {

    override fun getCursor(): Cursor {
        return mmsSmsDatabase.getConversation(threadID, reverse)
    }
}