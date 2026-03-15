package org.thoughtcrime.securesms.conversation.v3

import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord

class ConversationPagingSource(
    private val threadId: Long,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val reverse: Boolean,
    private val dataMapper: ConversationDataMapper,
    private val threadRecipient: Recipient,
    private val localUserAddress: String,
    private val lastSentMessageId: MessageId?,
    private val lastSeen: Long?
) : PagingSource<Int, ConversationDataMapper.ConversationItem>() {

    override fun getRefreshKey(state: PagingState<Int, ConversationDataMapper.ConversationItem>): Int? =
        state.anchorPosition?.let { anchor ->
            // Snap refresh back to the anchor page so scroll position is preserved
            val page = state.closestPageToPosition(anchor)
            page?.prevKey?.plus(state.config.pageSize)
                ?: page?.nextKey?.minus(state.config.pageSize)
        }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ConversationDataMapper.ConversationItem> {
        val offset = params.key ?: 0
        return try {
            val fetchOffset = (offset - 1).coerceAtLeast(0)
            val fetchSize = params.loadSize + if (offset > 0) 2 else 1

            val fetchedRecords = mmsSmsDatabase.getConversation(
                threadId, reverse, fetchOffset.toLong(), fetchSize.toLong()
            ).use { cursor ->
                buildList(cursor.count) {
                    val reader = mmsSmsDatabase.readerFor(cursor)
                    var record = reader.getNext()
                    while (record != null) {
                        add(record)
                        record = reader.getNext()
                    }
                }
            }

            val pageWindow = PageWindow.fromFetchedRecords(
                fetchedRecords = fetchedRecords,
                offset = offset,
                requestedLoadSize = params.loadSize,
            )

            val mapped = mutableListOf<ConversationDataMapper.ConversationItem>()
            for (i in pageWindow.records.indices) {
                dataMapper.map(
                    record = pageWindow.records[i],
                    newer = if (i == 0) pageWindow.newerNeighbor else pageWindow.records[i - 1],
                    older = if (i == pageWindow.records.lastIndex) pageWindow.olderNeighbor else pageWindow.records[i + 1],
                    threadRecipient = threadRecipient,
                    localUserAddress = localUserAddress,
                    showStatus = pageWindow.records[i].messageId == lastSentMessageId,
                    lastSeen = lastSeen,
                    out = mapped,
                )
            }

            LoadResult.Page(
                data = mapped,
                prevKey = if (offset == 0) null else maxOf(0, offset - params.loadSize),
                nextKey = if (pageWindow.records.size < params.loadSize) null else offset + pageWindow.records.size,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    /**
     * Holds the page records that will actually be emitted, plus one neighbor on each side when
     * available. The overlap records are not emitted; they only exist so the mapper can compute
     * visual state like clustering, author labels, date breaks, and unread markers correctly at
     * page boundaries.
     */
    private data class PageWindow(
        val records: List<MessageRecord>,
        val newerNeighbor: MessageRecord?,
        val olderNeighbor: MessageRecord?,
    ) {
        companion object {
            fun fromFetchedRecords(
                fetchedRecords: List<MessageRecord>,
                offset: Int,
                requestedLoadSize: Int,
            ): PageWindow {
                if (fetchedRecords.isEmpty()) {
                    return PageWindow(
                        records = emptyList(),
                        newerNeighbor = null,
                        olderNeighbor = null,
                    )
                }

                val hasNewerNeighbor = offset > 0
                val startIndex = if (hasNewerNeighbor) 1 else 0
                val safeStartIndex = startIndex.coerceAtMost(fetchedRecords.size)
                val desiredEndExclusive = (safeStartIndex + requestedLoadSize).coerceAtMost(fetchedRecords.size)
                val hasOlderNeighbor = fetchedRecords.size > desiredEndExclusive
                val endExclusive = desiredEndExclusive

                return PageWindow(
                    records = fetchedRecords.subList(safeStartIndex, endExclusive),
                    newerNeighbor = fetchedRecords.getOrNull(safeStartIndex - 1),
                    olderNeighbor = if (hasOlderNeighbor) fetchedRecords[endExclusive] else null,
                )
            }
        }
    }
}
