package org.thoughtcrime.securesms.conversation.v2

import android.content.Context
import android.database.Cursor
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.bumptech.glide.RequestManager
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.conversation.v2.messages.ControlMessageView
import org.thoughtcrime.securesms.conversation.v2.messages.VisibleMessageView
import org.thoughtcrime.securesms.conversation.v2.messages.VisibleMessageViewDelegate
import org.thoughtcrime.securesms.database.CursorRecyclerViewAdapter
import org.thoughtcrime.securesms.database.MmsSmsColumns
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

class ConversationAdapter(
    context: Context,
    originalLastSeen: Long,
    private val isReversed: Boolean,
    private val onItemPress: (MessageRecord, Int, VisibleMessageView, MotionEvent) -> Unit,
    private val onItemSwipeToReply: (MessageRecord, Int) -> Unit,
    private val onItemLongPress: (MessageRecord, Int, View) -> Unit,
    private val onDeselect: (MessageRecord) -> Unit,
    private val downloadPendingAttachment: (DatabaseAttachment) -> Unit,
    private val retryFailedAttachments: (List<DatabaseAttachment>) -> Unit,
    private val glide: RequestManager,
    private val threadRecipientProvider: () -> Recipient,
) : CursorRecyclerViewAdapter<ViewHolder>(context) {
    private val messageDB by lazy { DatabaseComponent.get(context).mmsSmsDatabase() }
    var selectedItems = mutableSetOf<MessageRecord>()
    var isAdmin: Boolean = false
    private var searchQuery: String? = null
    var visibleMessageViewDelegate: VisibleMessageViewDelegate? = null

    private val lastSeen = AtomicLong(originalLastSeen)

    var lastSentMessageId: MessageId? = null
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    private val expandedMessageIds = mutableSetOf<MessageId>()

    init {
        setHasStableIds(true)
    }

    class VisibleMessageViewHolder(val view: VisibleMessageView) : ViewHolder(view)
    class ControlMessageViewHolder(val view: ControlMessageView) : ViewHolder(view)

    override fun getItemViewType(cursor: Cursor): Int {
        val message = getMessage(cursor)!!
        if (message.isControlMessage) { return VIEW_TYPE_CONTROL }
        return VIEW_TYPE_VISIBLE
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        return when (viewType) {
            VIEW_TYPE_VISIBLE -> VisibleMessageViewHolder(VisibleMessageView(context))
            VIEW_TYPE_CONTROL -> ControlMessageViewHolder(ControlMessageView(context))
            else -> throw IllegalStateException("Unexpected view type: $viewType.")
        }
    }

    override fun getItemId(cursor: Cursor): Long {
        // Try to make a unique id out of the real message id, which is a composite structure: (id, is_mms)
        // It assumes that the id won't exceed a signed long, which is not the most correct but it's good enough.
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MmsSmsColumns.ID))
        val isMms = cursor.getString(cursor.getColumnIndexOrThrow(MmsSmsDatabase.TRANSPORT)) == MmsSmsDatabase.SMS_TRANSPORT
        return if (isMms) id else -id
    }

    override fun onBindItemViewHolder(viewHolder: ViewHolder, cursor: Cursor, position: Int) {
        val message = getMessage(cursor)!!
        val messageBefore = getMessageBefore(position, cursor)
        when (viewHolder) {
            is VisibleMessageViewHolder -> {
                val visibleMessageView = viewHolder.view
                val isSelected = selectedItems.contains(message)
                visibleMessageView.isMessageSelected = isSelected
                visibleMessageView.indexInAdapter = position
                val isExpanded = expandedMessageIds.contains(message.messageId)

                visibleMessageView.bind(
                    message = message,
                    threadRecipient = threadRecipientProvider(),
                    previous = messageBefore,
                    next = getMessageAfter(position, cursor),
                    glide = glide,
                    searchQuery = searchQuery,
                    lastSeen = lastSeen.get(),
                    lastSentMessageId = lastSentMessageId,
                    delegate = visibleMessageViewDelegate,
                    downloadPendingAttachment = downloadPendingAttachment,
                    retryFailedAttachments = retryFailedAttachments,
                    isTextExpanded = isExpanded,
                    onTextExpanded = { messageId ->
                        expandedMessageIds.add(messageId)
                    }
                )

                if (!message.isDeleted) {
                    visibleMessageView.onPress = { event ->
                        onItemPress(
                            message,
                            viewHolder.adapterPosition,
                            visibleMessageView,
                            event
                        )
                    }
                    visibleMessageView.onSwipeToReply =
                        { onItemSwipeToReply(message, viewHolder.adapterPosition) }
                    visibleMessageView.onLongPress =
                        { onItemLongPress(message, viewHolder.adapterPosition, visibleMessageView) }
                } else {
                    visibleMessageView.onPress = null
                    visibleMessageView.onSwipeToReply = null
                    // you can long press on "marked as deleted" messages
                    visibleMessageView.onLongPress =
                        { onItemLongPress(message, viewHolder.adapterPosition, visibleMessageView) }
                }
            }

            is ControlMessageViewHolder -> {
                viewHolder.view.bind(
                    message = message,
                    previous = messageBefore,
                    longPress = { onItemLongPress(message, viewHolder.adapterPosition, viewHolder.view) }
                )
            }
        }
    }

    private fun getItemPositionForId(target: MessageId): Int? {
        val c = cursor ?: return null
        for (i in 0 until itemCount) {
            if (!c.moveToPosition(i)) break
            val rec = messageDB.readerFor(c).current ?: continue
            if (rec.messageId == target) return i
        }
        return null
    }

    fun toggleSelection(message: MessageRecord) {
        if (selectedItems.contains(message)) selectedItems.remove(message) else selectedItems.add(message)
        getItemPositionForId(message.messageId)?.let { notifyItemChanged(it) }
    }

    override fun onItemViewRecycled(viewHolder: ViewHolder?) {
        when (viewHolder) {
            is VisibleMessageViewHolder -> viewHolder.view.recycle()
            is ControlMessageViewHolder -> viewHolder.view.recycle()
        }
        super.onItemViewRecycled(viewHolder)
    }

    private fun getMessage(cursor: Cursor): MessageRecord? = messageDB.readerFor(cursor).current

    private fun getMessageBefore(position: Int, cursor: Cursor): MessageRecord? {
        // The message that's visually before the current one is actually after the current
        // one for the cursor because the layout is reversed
        if (isReversed &&  !cursor.moveToPosition(position + 1)) { return null }
        if (!isReversed && !cursor.moveToPosition(position - 1)) { return null }

        return messageDB.readerFor(cursor).current
    }

    private fun getMessageAfter(position: Int, cursor: Cursor): MessageRecord? {
        // The message that's visually after the current one is actually before the current
        // one for the cursor because the layout is reversed
        if (isReversed && !cursor.moveToPosition(position - 1)) { return null }
        if (!isReversed && !cursor.moveToPosition(position + 1)) { return null }

        return messageDB.readerFor(cursor).current
    }

    override fun changeCursor(cursor: Cursor?) {
        super.changeCursor(cursor)

        val toRemove = mutableSetOf<MessageRecord>()
        val toDeselect = mutableSetOf<MessageRecord>()
        for (selected in selectedItems) {
            val position = getItemPositionForId(selected.messageId)
            if (position == null || position == -1) {
                toRemove += selected
            } else {
                val item = cursor?.let {
                    it.moveToPosition(position)
                    getMessage(it)
                }

                if (item == null || item.isDeleted) {
                    toDeselect += selected
                }
            }
        }
        selectedItems -= toRemove
        toDeselect.iterator().forEach { record ->
            onDeselect(record)
        }
    }

    fun findLastSeenItemPosition(lastSeenTimestamp: Long): Int? {
        val cursor = this.cursor
        if (cursor == null || !isActiveCursor) return null
        if (lastSeenTimestamp == 0L) {
            if (isReversed && cursor.moveToLast()) { return cursor.position }
            if (!isReversed && cursor.moveToFirst()) { return cursor.position }
        }

        // Loop from the newest message to the oldest until we find one older (or equal to)
        // the lastSeenTimestamp, then return that message index
        for (i in 0 until itemCount) {
            if (isReversed) {
                cursor.moveToPosition(i)
                val (outgoing, dateSent) = messageDB.timestampAndDirectionForCurrent(cursor)
                if (outgoing || dateSent <= lastSeenTimestamp) {
                    return i
                }
            }
            else {
                val index = ((itemCount - 1) - i)
                cursor.moveToPosition(index)
                val (outgoing, dateSent) = messageDB.timestampAndDirectionForCurrent(cursor)
                if (outgoing || dateSent <= lastSeenTimestamp) {
                    return min(itemCount - 1, (index + 1))
                }
            }
        }
        return null
    }

    fun getItemPositionForTimestamp(timestamp: Long): Int? {
        val cursor = this.cursor
        if (timestamp <= 0L || cursor == null || !isActiveCursor) return null
        for (i in 0 until itemCount) {
            cursor.moveToPosition(i)
            val (_, dateSent) = messageDB.timestampAndDirectionForCurrent(cursor)
            if (dateSent == timestamp) { return i }
        }
        return null
    }

    fun onSearchQueryUpdated(query: String?) {
        this.searchQuery = query
        notifyDataSetChanged()
    }

    fun getTimestampForItemAt(firstVisiblePosition: Int): Long? {
        val cursor = this.cursor ?: return null
        if (!cursor.moveToPosition(firstVisiblePosition)) return null
        val message = messageDB.readerFor(cursor).current ?: return null
        if (message.reactions.isEmpty()) {
            // If the message has no reactions, we can use the timestamp directly
            return message.timestamp
        }

        // Otherwise, we will need to take the reaction timestamp into account
        val maxReactionTimestamp = message.reactions.maxOf { it.dateReceived }
        return max(message.timestamp, maxReactionTimestamp)
    }

    companion object {
        private const val VIEW_TYPE_VISIBLE = 1
        private const val VIEW_TYPE_CONTROL = 2
    }
}