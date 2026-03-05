package org.thoughtcrime.securesms.conversation.v3.compose.conversation

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.yield
import org.thoughtcrime.securesms.conversation.v3.ConversationDataMapper.ConversationItem
import org.thoughtcrime.securesms.conversation.v3.ConversationV3ViewModel.ScrollEvent
import org.thoughtcrime.securesms.conversation.v3.compose.message.HighlightMessage
import org.thoughtcrime.securesms.database.model.MessageId
import kotlin.math.abs

/**
 * Single point of control for conversation list scrolling and highlighting.
 *
 * Any feature that needs "go to message X" or "go to bottom" should call
 * [handleScrollEvent].
 *
 * **Why `animateScrollBy` instead of `animateScrollToItem`:**
 * Compose's `animateScrollToItem` estimates scroll distance from average
 * item heights. With variable-height chat messages, date breaks, and a
 * reversed layout, the estimate is often wrong — causing a visible
 * two-step correction. `animateScrollBy` works in raw pixels and lets
 * the list clamp at edges, avoiding this entirely.
 */
@Stable
class ConversationListState(
    val lazyListState: LazyListState,
) {
    companion object {
        // how many messages needed before we jump to a  message instead of simply scrolling to it
        private const val JUMP_THRESHOLD = 20

        // when jumping, how far away do we jump
        private const val JUMP_PROXIMITY = 10

        // animateScrollBy clamps at list edges, so overshooting is safe.
        private const val LARGE_SCROLL_PX = 1_000_000f
    }

    // ── Highlight ──
    /** Current highlight target. Composable reads this per-item via [highlightKeyFor]. */
    var currentHighlight by mutableStateOf<HighlightTarget?>(null)
        private set

    /**
     * Returns a [HighlightMessage] key if [messageId] is the current highlight target,
     * null otherwise. Cheap equality check — non-highlighted items skip recomposition.
     */
    fun highlightKeyFor(messageId: MessageId): HighlightMessage? {
        return currentHighlight?.takeIf { it.messageId == messageId }?.key
    }

    data class HighlightTarget(
        val messageId: MessageId,
        val key: HighlightMessage,
    )

    // ── Public API ──
    /**
     * Handle a scroll event. This is the **only** method external code needs to call
     * for scrolling.
     *
     * @param event        What to scroll to.
     * @param pagingItems  Current paging snapshot, needed for index resolution.
     */
    suspend fun handleScrollEvent(
        event: ScrollEvent,
        pagingItems: LazyPagingItems<ConversationItem>,
    ) {
        when (event) {
            is ScrollEvent.ToBottom -> {
                jumpIfFar(targetIndex = 0)
                // Overshoot toward bottom — list clamps at the edge.
                lazyListState.animateScrollBy(-LARGE_SCROLL_PX)
            }

            is ScrollEvent.ToMessage -> {
                // Clear any previous highlight before scrolling so stale keys
                // don't trigger a premature animation as the target item
                // comes into view mid-scroll.
                currentHighlight = null

                val index = pagingItems.findIndexOf(event.messageId) ?: return

                jumpIfFar(targetIndex = index)
                animateToCentered(index)

                if (event.highlight) {
                    currentHighlight = HighlightTarget(
                        messageId = event.messageId,
                        key = HighlightMessage(token = System.nanoTime()),
                    )
                }
            }
        }
    }

    fun clearHighlight(messageId: MessageId, key: HighlightMessage) {
        val cur = currentHighlight ?: return
        if (cur.messageId == messageId && cur.key == key) {
            currentHighlight = null
        }
    }

    // ── Internal ──

    /**
     * If the target is far away, jump close first and yield a frame
     * so Compose lays out nearby items with their real heights.
     */
    private suspend fun jumpIfFar(targetIndex: Int) {
        val firstVisible = lazyListState.firstVisibleItemIndex
        val distance = abs(targetIndex - firstVisible)

        if (distance > JUMP_THRESHOLD) {
            val total = lazyListState.layoutInfo.totalItemsCount
            val jumpTo = if (targetIndex > firstVisible) {
                (targetIndex - JUMP_PROXIMITY).coerceAtLeast(0)
            } else {
                (targetIndex + JUMP_PROXIMITY).coerceAtMost((total - 1).coerceAtLeast(0))
            }
            lazyListState.scrollToItem(jumpTo)
            yield()
        }
    }

    /**
     * Animate the item into a centered position using exact pixel math.
     *
     * After [jumpIfFar], the target is nearby and typically in
     * [visibleItemsInfo] so we can read its real offset and height.
     * Short items get centered; tall items show the top.
     */
    private suspend fun animateToCentered(index: Int) {
        val itemInfo = lazyListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == index }

        if (itemInfo != null) {
            val viewportHeight = lazyListState.layoutInfo.viewportSize.height
            val itemHeight = itemInfo.size

            val centeringOffset = if (itemHeight < viewportHeight) {
                (viewportHeight - itemHeight) / 2
            } else {
                viewportHeight * 7 / 8
            }

            lazyListState.animateScrollBy((itemInfo.offset - centeringOffset).toFloat())
        } else {
            // Fallback — item not visible after jump
            lazyListState.animateScrollToItem(index)
        }
    }
}

// ── Paging helpers ──
/**
 * Find the index of a message in the currently loaded paging snapshot.
 * Returns null if the message hasn't been paged in yet.
 */
private fun LazyPagingItems<ConversationItem>.findIndexOf(
    messageId: MessageId,
): Int? {
    val snapshot = itemSnapshotList
    for (i in snapshot.indices) {
        if ((snapshot[i] as? ConversationItem.Message)?.data?.id == messageId) {
            return i
        }
    }
    return null
}

// ── Remember ──

@Composable
fun rememberConversationListState(): ConversationListState {
    val lazyListState = rememberLazyListState()
    return remember(lazyListState) {
        ConversationListState(lazyListState = lazyListState)
    }
}