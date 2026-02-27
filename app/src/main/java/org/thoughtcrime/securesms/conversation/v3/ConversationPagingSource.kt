package org.thoughtcrime.securesms.conversation.v3

import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.conversation.v3.compose.MessageViewData
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
) : PagingSource<Int, MessageViewData>() {

    override fun getRefreshKey(state: PagingState<Int, MessageViewData>): Int? =
        state.anchorPosition?.let { anchor ->
            // Snap refresh back to the anchor page so scroll position is preserved
            val page = state.closestPageToPosition(anchor)
            page?.prevKey?.plus(state.config.pageSize)
                ?: page?.nextKey?.minus(state.config.pageSize)
        }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MessageViewData> {
        val offset = params.key ?: 0
        return try {
            // getConversation already handles LIMIT/OFFSET in SQL
            val records = mmsSmsDatabase.getConversation(
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

            val mapped = records.mapIndexed { index, record ->
                dataMapper.map(
                    record = record,
                    previous = records.getOrNull(index + 1),
                    threadRecipient = threadRecipient,
                    localUserAddress = localUserAddress,
                    showStatus = record.messageId == lastSentMessageId,
                )
            }

            LoadResult.Page(
                data = mapped,
                prevKey = if (offset == 0) null else maxOf(0, offset - params.loadSize),
                nextKey = if (records.size < params.loadSize) null else offset + params.loadSize,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}