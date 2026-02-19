package org.thoughtcrime.securesms.reactions;

import androidx.annotation.NonNull;

import java.util.List;

import kotlin.collections.CollectionsKt;

public final class EmojiCount {

  private final String                baseEmoji;
  private final String                displayEmoji;
  private final List<ReactionDetails> reactions;
  private final boolean shouldAccumulateReactionCount;

  EmojiCount(@NonNull String baseEmoji,
             @NonNull String emoji,
             @NonNull List<ReactionDetails> reactions,
             boolean shouldAccumulateReactionCount)
  {
    this.baseEmoji    = baseEmoji;
    this.displayEmoji = emoji;
    this.reactions    = reactions;
    this.shouldAccumulateReactionCount = shouldAccumulateReactionCount;
  }

  public @NonNull String getBaseEmoji() {
    return baseEmoji;
  }

  public @NonNull String getDisplayEmoji() {
    return displayEmoji;
  }

  public int getCount() {
    if (shouldAccumulateReactionCount) {
       return CollectionsKt.fold(reactions, 0, (count, reaction) -> count + reaction.getCount());
    }

      ReactionDetails first = CollectionsKt.getOrNull(reactions, 0);
      return first == null ? 0 : first.getCount();
  }

  public @NonNull List<ReactionDetails> getReactions() {
    return reactions;
  }
}
