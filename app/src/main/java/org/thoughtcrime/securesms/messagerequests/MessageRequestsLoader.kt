package org.thoughtcrime.securesms.messagerequests

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import org.thoughtcrime.securesms.util.AbstractCursorLoader

class MessageRequestsLoader(
    private val threadDatabase: ThreadDatabase,
    context: Context
) : AbstractCursorLoader(context) {

    override fun getCursor(): Cursor? {
        return threadDatabase.unapprovedConversationList
    }
}