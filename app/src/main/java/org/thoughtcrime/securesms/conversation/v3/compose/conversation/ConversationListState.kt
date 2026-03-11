package org.thoughtcrime.securesms.conversation.v3.compose.conversation

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
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
 */
@Stable
class ConversationListState(
    val lazyListState: LazyListState,
) {
    companion object {
        // How many messages away before we do an invisible jump first.
        private const val JUMP_THRESHOLD = 20

        // When far away, jump close to the target so the final scroll still feels
        // like a local movement instead of a whole-screen teleport.
        private const val JUMP_PROXIMITY = 10

        // animateScrollBy clamps at list edges, so overshooting is safe.
        private const val LARGE_SCROLL_PX = 1_000_000f

        // Defensive timeout so a lost paging request cannot suspend a scroll forever.
        private const val PAGE_LOAD_TIMEOUT_MS = 5_000L
    }

    // ── Highlight ──
    /**
     * Current highlight target. Message rows read this via [highlightKeyFor].
     */
    var currentHighlight by mutableStateOf<HighlightTarget?>(null)
        private set

    /**
     * Returns a [HighlightMessage] key if [messageId] is the current highlight target,
     * null otherwise.
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
     * Handle a scroll event. This is the single entry point for list scrolling.
     *
     * @param event What to scroll to.
     * @param pagingItems Current paging snapshot, needed for index resolution.
     */
    suspend fun handleScrollEvent(
        event: ScrollEvent,
        pagingItems: LazyPagingItems<ConversationItem>,
    ) {
        when (event) {
            is ScrollEvent.ToBottom -> {
                jumpIfFar(targetIndex = 0)
                lazyListState.animateScrollBy(-LARGE_SCROLL_PX)
            }

            is ScrollEvent.ToMessage -> {
                currentHighlight = null

                val index = pagingItems.findLoadedIndexOrLoadOlderPages(event.messageId) ?: return

                if (event.smoothScroll) {
                    jumpIfFar(targetIndex = index)
                    animateToPositioned(index)
                } else {
                    lazyListState.scrollToItem(index)
                    yield() // let Compose remeasure before reading item geometry
                    snapToPosition(index)
                }

                if (event.highlight) {
                    currentHighlight = HighlightTarget(
                        messageId = event.messageId,
                        key = HighlightMessage(token = System.nanoTime()),
                    )
                }
            }
        }
    }

    /**
     * Offset the scroll to cater for the message expanding
     */
    fun scrollForMessageExpansion(
        messageId: MessageId,
        extraHeightPx: Int,
        pagingItems: LazyPagingItems<ConversationItem>,
    ) {
        val index = pagingItems.findIndexOf(messageId) ?: return
        val currentOffset = lazyListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == index }
            ?.offset
            ?: return

        lazyListState.requestScrollToItem(
            index = index,
            scrollOffset = extraHeightPx - currentOffset,
        )
    }

    // ── Internal ──

    /**
     * If the target is far away, jump close to it first so the visible pass still
     * feels like a short local movement. If that still is not enough to materialize
     * the real row, [ensureTargetAndGetDelta] will fall back to the exact item.
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
            lazyListState.scrollToItem(
                index = jumpTo,
                scrollOffset = scrollOffset(),
            )
            yield()
        }
    }

    /**
     * One visible positioning animation using the measured item height.
     *
     * Short items are centered. Tall items bias toward showing the start of the
     * message instead of trying to center the whole thing.
     */
    private suspend fun animateToPositioned(index: Int) {
        val delta = ensureTargetAndGetDelta(index) ?: return
        if (abs(delta) <= 1) return
        lazyListState.animateScrollBy(delta.toFloat())
    }

    private suspend fun snapToPosition(index: Int) {
        val delta = ensureTargetAndGetDelta(index) ?: return
        if (abs(delta) <= 1) return
        lazyListState.scrollBy(delta.toFloat())
    }

    /**
     * If the target still is not visible, materialize the exact item first, then read
     * its measured geometry.
     */
    private suspend fun ensureTargetAndGetDelta(index: Int): Int? {
        var itemInfo = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }

        if (itemInfo == null) {
            lazyListState.scrollToItem(
                index = index,
                scrollOffset = scrollOffset(),
            )
            yield()
            itemInfo = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        }

        val visibleItem = itemInfo ?: return null
        val viewportHeight = lazyListState.layoutInfo.viewportSize.height
        val itemHeight = visibleItem.size

        val desiredOffset = if (itemHeight < viewportHeight) {
            (viewportHeight - itemHeight) / 2
        } else {
            // Tall messages should reveal their start, not sit low in the viewport.
            viewportHeight / 8
        }

        return visibleItem.offset - desiredOffset
    }

    private fun scrollOffset(): Int {
        return -(lazyListState.layoutInfo.viewportSize.height / 2)
    }

    /**
     * Resolve a message id to a loaded list index.
     *
     * The conversation starts at the newest messages, so if the target is not in the current
     * snapshot we progressively load older pages until it appears or paging tells us there are no
     * more results.
     */
    private suspend fun LazyPagingItems<ConversationItem>.findLoadedIndexOrLoadOlderPages(
        messageId: MessageId,
    ): Int? {
        // A scroll request can arrive before the initial refresh has finished.
        if (!awaitInitialRefresh()) return null

        while (true) {
            findIndexOf(messageId)?.let { return it }
            if (!loadNextOlderPage()) return null
        }
    }

    /**
     * Wait until the initial refresh has either populated the snapshot, finished empty, or failed.
     */
    private suspend fun LazyPagingItems<ConversationItem>.awaitInitialRefresh(): Boolean {
        return withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) {
            snapshotFlow { itemCount to loadState.refresh }
                .mapNotNull { (count, refreshState) ->
                    when {
                        count > 0 -> true
                        refreshState is LoadState.NotLoading -> true
                        refreshState is LoadState.Error -> false
                        else -> null
                    }
                }
                .first()
        } ?: false
    }

    /**
     * Touch the oldest loaded item so Paging can schedule an append load for the next older page.
     *
     * In this conversation list, older messages live at the end of the loaded snapshot.
     */
    private suspend fun LazyPagingItems<ConversationItem>.requestNextOlderPage(): Boolean {
        val lastLoadedIndex = itemCount - 1
        if (lastLoadedIndex < 0) return false

        val previousItemCount = itemCount
        this[lastLoadedIndex]

        return awaitOlderPageResult(previousItemCount)
    }

    /**
     * Advance the snapshot by one older page.
     *
     * If an append already is in flight, we wait for it instead of triggering another touch on the
     * list tail.
     */
    private suspend fun LazyPagingItems<ConversationItem>.loadNextOlderPage(): Boolean {
        return when (val appendState = loadState.append) {
            is LoadState.NotLoading -> {
                if (appendState.endOfPaginationReached) {
                    false
                } else {
                    requestNextOlderPage()
                }
            }

            is LoadState.Loading -> {
                awaitOlderPageResult(previousItemCount = itemCount)
            }

            is LoadState.Error -> false
        }
    }

    /**
     * Wait for the older-page append request to either grow the snapshot or terminate.
     *
     * A `false` result means paging reached the end, failed, or timed out before the snapshot grew.
     */
    private suspend fun LazyPagingItems<ConversationItem>.awaitOlderPageResult(
        previousItemCount: Int,
    ): Boolean {
        if (itemCount > previousItemCount) return true

        return withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) {
            snapshotFlow { itemCount to loadState.append }
                .mapNotNull { (count, appendState) ->
                    when {
                        count > previousItemCount -> true
                        appendState is LoadState.NotLoading && appendState.endOfPaginationReached -> false
                        appendState is LoadState.Error -> false
                        else -> null
                    }
                }
                .first()
        } ?: false
    }
}

/**
 * Find the index of a message in the currently loaded paging snapshot.
 * Returns null if the message has not been paged in yet.
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

/**
 * Remember the shared controller for scrolling and highlighting the conversation list.
 */
@Composable
fun rememberConversationListState(): ConversationListState {
    val lazyListState = rememberLazyListState()
    return remember(lazyListState) {
        ConversationListState(lazyListState = lazyListState)
    }
}

internal fun messageItemKey(messageId: MessageId): String = "msg_$messageId"
