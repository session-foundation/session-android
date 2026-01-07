package org.thoughtcrime.securesms.conversation.v2

import android.app.Application
import android.database.ContentObserver
import android.database.Cursor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.util.AbstractCursorLoader

class ConversationLoader @AssistedInject constructor(
    @Assisted private val threadID: Long,
    @Assisted private val reverse: Boolean,
    application: Application,
    private val mmsSmsDatabase: MmsSmsDatabase,
) : AbstractCursorLoader<ConversationLoader.Data>(application) {

    override fun getData(): Data {
        return Data(
            messageCursor = mmsSmsDatabase.getConversation(threadID, reverse),
            threadUnreadCount = mmsSmsDatabase.getUnreadCount(threadID),
        )
    }

    data class Data(
        val messageCursor: Cursor,
        val threadUnreadCount: Int,
    ) : CursorLike {
        override fun close() = messageCursor.close()
        override fun isClosed() = messageCursor.isClosed
        override fun getCount() = messageCursor.count
        override fun registerContentObserver(observer: ContentObserver?)
            = messageCursor.registerContentObserver(observer)
    }
    
    @AssistedFactory
    interface Factory {
        fun create(threadID: Long, reverse: Boolean): ConversationLoader
    }
}