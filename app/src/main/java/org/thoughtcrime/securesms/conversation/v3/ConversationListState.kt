package org.thoughtcrime.securesms.conversation.v3

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import org.thoughtcrime.securesms.conversation.v3.ConversationDataMapper.ConversationItem
import org.thoughtcrime.securesms.conversation.v3.ConversationV3ViewModel.ScrollEvent
import org.thoughtcrime.securesms.conversation.v3.compose.message.HighlightMessage
import org.thoughtcrime.securesms.database.model.MessageId
import kotlin.math.abs

/**
 * Single point of control for conversation list scrolling and highlighting.
 *
 * Any feature that needs "go to message X" or "go to bottom" calls
 * [handleScrollEvent]. Scrolling, index resolution, jump-then-animate
 * optimisation, and highlight triggering are all internal details.
 *
 * Usage:
 * ```
 * val listController = rememberConversationListState()
 *
 * LaunchedEffect(Unit) {
 *     scrollEvents.collect { listController.handleScrollEvent(it, pagingItems) }
 * }
 * ```
 */
@Stable
class ConversationListState(
    val lazyListState: LazyListState,
    private val breathingRoomPx: Int,
) {
    companion object {
        // how many messages needed before we jump to a  message instead of simply scrolling to it
        private const val JUMP_THRESHOLD = 20

        // when jumping, how far away do we jump
        private const val JUMP_PROXIMITY = 10
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

    // ── Scrolling ──

    /**
     * Handle a scroll event. This is the **only** method external code needs to call.
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
                scrollTo(index = 0, smoothScroll = true)
            }

            is ScrollEvent.ToMessage -> {
                val index = pagingItems.findIndexOf(event.messageId) ?: return

                scrollTo(
                    index = index,
                    smoothScroll = event.smoothScroll,
                    scrollOffset = -breathingRoomPx,
                )

                if (event.highlight) {
                    currentHighlight = HighlightTarget(
                        messageId = event.messageId,
                        key = HighlightMessage(token = System.nanoTime()),
                    )
                }
            }
        }
    }

    // ── Internal ──

    private suspend fun scrollTo(
        index: Int,
        smoothScroll: Boolean,
        scrollOffset: Int = 0,
    ) {
        if (!smoothScroll) {
            lazyListState.scrollToItem(index, scrollOffset)
            return
        }

        val distance = abs(index - lazyListState.firstVisibleItemIndex)
        if (distance > JUMP_THRESHOLD) {
            val total = lazyListState.layoutInfo.totalItemsCount
            val jumpTo = if (index > lazyListState.firstVisibleItemIndex) {
                (index - JUMP_PROXIMITY).coerceAtLeast(0)
            } else {
                (index + JUMP_PROXIMITY).coerceAtMost((total - 1).coerceAtLeast(0))
            }
            lazyListState.scrollToItem(jumpTo)
        }

        lazyListState.animateScrollToItem(index, scrollOffset)
    }
}

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

@Composable
fun rememberConversationListState(): ConversationListState {
    val lazyListState = rememberLazyListState()
    val density = LocalDensity.current
    val breathingRoomPx = remember(density) { with(density) { 32.dp.roundToPx() } }

    return remember(lazyListState, breathingRoomPx) {
        ConversationListState(
            lazyListState = lazyListState,
            breathingRoomPx = breathingRoomPx,
        )
    }
}