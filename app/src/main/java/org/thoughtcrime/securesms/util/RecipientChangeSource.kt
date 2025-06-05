package org.thoughtcrime.securesms.util

import android.content.ContentResolver
import app.cash.copper.Query
import app.cash.copper.flow.observeQuery
import kotlinx.coroutines.flow.Flow
import org.thoughtcrime.securesms.database.DatabaseContentProviders

/** Emits every time the Recipients table changes. */
interface RecipientChangeSource {
    fun changes(): Flow<Query>
}

/** Real implementation used in production. */
class ContentObserverRecipientChangeSource(
    private val contentResolver: ContentResolver
) : RecipientChangeSource {
    override fun changes(): Flow<Query> =
        contentResolver.observeQuery(DatabaseContentProviders.Recipient.CONTENT_URI)
}