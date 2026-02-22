package org.thoughtcrime.securesms.conversation.v3.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

data class EmojiReactionItem(
    val emoji: String,
    val count: Long,
    val selected: Boolean,
)


private const val REACTIONS_THRESHOLD = 5

@Composable
fun EmojiReactions(
    reactions: List<EmojiReactionItem>,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    onReactionClick: (emoji: String) -> Unit = {},
    onReactionLongClick: (emoji: String) -> Unit = {},
    onExpandClick: () -> Unit = {},
    onShowLessClick: () -> Unit = {},
) {
    val hasOverflow = !isExpanded && reactions.size > REACTIONS_THRESHOLD
    // When collapsed: show the first (THRESHOLD - 1) pills then the overflow slot,
    // so total slots == THRESHOLD
    val visibleReactions = if (hasOverflow) reactions.take(REACTIONS_THRESHOLD - 1) else reactions
    val overflowReactions = if (hasOverflow) reactions.drop(REACTIONS_THRESHOLD - 1) else emptyList()

    Column(modifier = modifier.wrapContentWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            visibleReactions.forEach { reaction ->
                EmojiReactionPill(
                    reaction = reaction,
                    onClick = { onReactionClick(reaction.emoji) },
                    onLongClick = { onReactionLongClick(reaction.emoji) },
                )
            }
            SELECTED STATE ISNT RIGHT > CONTINUE SCANNING FILE > WRONG PILL SHAPES

            if (overflowReactions.isNotEmpty()) {
                EmojiReactionOverflow(
                    reactions = overflowReactions,
                    onClick = onExpandClick,
                )
            }
        }

        // "Show less" row — mirrors group_show_less visibility in original
        if (isExpanded && reactions.size > REACTIONS_THRESHOLD) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onShowLessClick)
                    .padding(
                        vertical = LocalDimensions.current.xsSpacing,
                    ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_up),
                    contentDescription = null,
                    tint = LocalColors.current.text,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.showLess),
                    style = LocalType.current.extraSmall,
                    color = LocalColors.current.text,
                    modifier = Modifier.padding(start = 1.dp),
                )
            }
        }
    }
}

/** A single reaction pill (emoji + count). */
@Composable
fun EmojiReactionPill(
    reaction: EmojiReactionItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val selected = reaction.selected

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(shape)
            .background(
                color = if (selected) LocalColors.current.accent
                else LocalColors.current.backgroundBubbleReceived,
                shape = shape,
            )
            .then(
                if (!selected) Modifier.border(
                    width = 1.dp,
                    color = LocalColors.current.borders,
                    shape = shape,
                ) else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = LocalDimensions.current.xsSpacing, vertical = 4.dp),
    ) {
        // Swap with your EmojiImageView interop wrapper if needed
        Text(
            text = reaction.emoji,
            style = LocalType.current.base,
        )

        if (reaction.count > 0) {
            Text(
                text = reaction.count.toString(),
                style = LocalType.current.small,
                color = if (selected) LocalColors.current.textBubbleSent
                else LocalColors.current.textBubbleReceived,
            )
        }
    }
}

/**
 * Compact stacked overflow pills — no count, overlapping horizontally.
 * Mirrors the original overflowContainer with negative right margins so pills
 * stack, and z-ordering so earlier items sit on top.
 */
@Composable
fun EmojiReactionOverflow(
    reactions: List<EmojiReactionItem>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillSize = 24.dp
    val overlapOffset = 8.dp // matches negativeMargin = dpToPx(-8) from original

    Box(
        modifier = modifier
            .size(
                width = pillSize + overlapOffset * (reactions.size - 1).coerceAtLeast(0),
                height = pillSize,
            )
            .clickable(onClick = onClick),
    ) {
        reactions.forEachIndexed { index, reaction ->
            val shape = RoundedCornerShape(50)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(pillSize)
                    .padding(start = overlapOffset * index)
                    .clip(shape)
                    .background(
                        color = LocalColors.current.backgroundBubbleReceived,
                        shape = shape,
                    )
                    .border(1.dp, LocalColors.current.borders, shape),
            ) {
                Text(
                    text = reaction.emoji,
                    style = LocalType.current.small,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview
@Composable
fun EmojiReactionsPreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors,
) {
    PreviewTheme(colors) {
        val sampleReactions = listOf(
            EmojiReactionItem("👍", 3, selected = true),
            EmojiReactionItem("❤️", 12, selected = false),
            EmojiReactionItem("😂", 1, selected = false),
            EmojiReactionItem("😮", 5, selected = false),
            EmojiReactionItem("😢", 2, selected = false),
            EmojiReactionItem("🔥", 8, selected = false),
            EmojiReactionItem("🔥", 8, selected = false),
            EmojiReactionItem("🔥", 8, selected = false),
            EmojiReactionItem("🔥", 8, selected = false),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            // Collapsed: first 4 pills + overflow slot
            EmojiReactions(reactions = sampleReactions, isExpanded = false)

            // Expanded: all 6 pills + show less
            EmojiReactions(reactions = sampleReactions, isExpanded = true)

            // Under threshold: all shown, no overflow, no show less
            EmojiReactions(reactions = sampleReactions.take(3), isExpanded = false)
        }
    }
}