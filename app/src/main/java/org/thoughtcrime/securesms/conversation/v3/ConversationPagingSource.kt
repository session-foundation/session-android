package org.thoughtcrime.securesms.conversation.v3

import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord

class ConversationPagingSource(
    private val threadId: Long,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val reverse: Boolean,
) : PagingSource<Int, MessageRecord>() {

    override fun getRefreshKey(state: PagingState<Int, MessageRecord>): Int? =
        state.anchorPosition?.let { anchor ->
            // Snap refresh back to the anchor page so scroll position is preserved
            val page = state.closestPageToPosition(anchor)
            page?.prevKey?.plus(state.config.pageSize)
                ?: page?.nextKey?.minus(state.config.pageSize)
        }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MessageRecord> {
        val offset = params.key ?: 0
        return try {
            // getConversation already handles LIMIT/OFFSET in SQL
            val messages = mmsSmsDatabase.getConversation(
                threadId, reverse, offset.toLong(), params.loadSize.toLong()
            ).use { cursor ->
                buildList {
                    val reader = mmsSmsDatabase.readerFor(cursor)
                    var record = reader.getNext()
                    while (record != null) {
                        add(record)
                        record = reader.getNext()
                    }
                }
            }
            LoadResult.Page(
                data = messages,
                prevKey = if (offset == 0) null else maxOf(0, offset - params.loadSize),
                nextKey = if (messages.size < params.loadSize) null else offset + params.loadSize
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}