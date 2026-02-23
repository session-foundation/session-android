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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.zIndex
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.SessionColorsParameterProvider
import org.thoughtcrime.securesms.ui.theme.ThemeColors

private const val REACTIONS_THRESHOLD = 5

@Composable
fun EmojiReactions(
    reactions: List<ReactionItem>,
    isExpanded: Boolean,
    outgoing: Boolean,
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
            horizontalArrangement = if (outgoing) {
                Arrangement.spacedBy(LocalDimensions.current.tinySpacing, Alignment.End)
            } else {
                Arrangement.spacedBy(LocalDimensions.current.tinySpacing, Alignment.Start)
            },
            verticalArrangement = Arrangement.spacedBy(LocalDimensions.current.tinySpacing),
        ) {
            visibleReactions.forEach { reaction ->
                EmojiReactionPill(
                    reaction = reaction,
                    onClick = { onReactionClick(reaction.emoji) },
                    onLongClick = { onReactionLongClick(reaction.emoji) },
                )
            }

            if (overflowReactions.isNotEmpty()) {
                EmojiReactionOverflow(
                    reactions = overflowReactions.take(3), // only use first 3
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
                    modifier = Modifier.size(LocalDimensions.current.iconXSmall),
                )
                Text(
                    text = stringResource(R.string.showLess),
                    style = LocalType.current.extraSmall,
                    color = LocalColors.current.text,
                    modifier = Modifier.padding(start = LocalDimensions.current.tinySpacing),
                )
            }
        }
    }
}

/** A single reaction pill (emoji + count). */
@Composable
fun EmojiReactionPill(
    reaction: ReactionItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.extraLarge
    val selected = reaction.selected

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalDimensions.current.xxxsSpacing),
        modifier = modifier
            .clip(shape)
            .background(
                color = LocalColors.current.backgroundBubbleReceived,
                shape = shape,
            )
            .border(
                width = 1.dp,
                color = if(selected) LocalColors.current.accent
                else LocalColors.current.background,
                shape = shape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = LocalDimensions.current.xxsSpacing,
                vertical = LocalDimensions.current.xxxsSpacing),
    ) {
        Text(
            text = reaction.emoji,
            style = LocalType.current.extraSmall,
        )

        if (reaction.count > 0) {
            Text(
                text = reaction.count.toString(),
                style = LocalType.current.small,
                color = LocalColors.current.text,
            )
        }
    }
}

/**
 * Compact stacked overflow pills — no count, overlapping horizontally.
 */
@Composable
fun EmojiReactionOverflow(
    reactions: List<ReactionItem>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillSize = LocalDimensions.current.iconMedium
    val overlapOffset = LocalDimensions.current.smallSpacing

    // We calculate the total width needed: size of one pill + (offset * remaining count)
    val totalWidth = pillSize + (overlapOffset * (reactions.size - 1))

    Box(
        modifier = modifier
            .width(totalWidth)
            .clickable(onClick = onClick),
    ) {
        reactions.forEachIndexed { index, reaction ->
            val shape = CircleShape
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(pillSize)
                    .offset(x = overlapOffset * index)
                    .zIndex(reactions.size - index.toFloat())
                    .background(
                        color = LocalColors.current.backgroundBubbleReceived,
                        shape = shape,
                    )
                    .border(1.dp, LocalColors.current.borders, shape)
                    .clip(shape),
            ) {
                Text(
                    text = reaction.emoji,
                    style = LocalType.current.extraSmall,
                )
            }
        }
    }
}

@Preview
@Composable
fun EmojiReactionsPreview(
    @PreviewParameter(SessionColorsParameterProvider::class) colors: ThemeColors,
) {
    PreviewTheme(colors) {
        val sampleReactions = listOf(
            ReactionItem("👍", 3, selected = true),
            ReactionItem("❤️", 12, selected = false),
            ReactionItem("😂", 1, selected = false),
            ReactionItem("😮", 5, selected = false),
            ReactionItem("😢", 2, selected = false),
            ReactionItem("🔥", 8, selected = false),
            ReactionItem("💕", 8, selected = false),
            ReactionItem("🐙", 8, selected = false),
            ReactionItem("✅", 8, selected = false),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            // Collapsed: first 4 pills + overflow slot
            EmojiReactions(reactions = sampleReactions, outgoing = false, isExpanded = false)

            // Expanded: all 6 pills + show less
            EmojiReactions(reactions = sampleReactions, outgoing = false, isExpanded = true)

            // Under threshold: all shown, no overflow, no show less
            EmojiReactions(reactions = sampleReactions.take(3), outgoing = false, isExpanded = false)
        }
    }
}