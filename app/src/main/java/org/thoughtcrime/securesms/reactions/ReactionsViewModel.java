package org.thoughtcrime.securesms.reactions;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.database.RecipientRepository;
import org.thoughtcrime.securesms.database.model.MessageId;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;

@HiltViewModel(assistedFactory = ReactionsViewModel.Factory.class)
public class ReactionsViewModel extends ViewModel {

  private final MessageId           messageId;
  private final ReactionsRepository repository;

  @AssistedInject
  public ReactionsViewModel(@Assisted @NonNull MessageId messageId, final ReactionsRepository repository) {
    this.messageId  = messageId;
    this.repository = repository;
  }

  public @NonNull
  Observable<List<EmojiCount>> getEmojiCounts() {
    return repository.getReactions(messageId)
                     .map(reactionList -> Stream.of(reactionList)
                                                          .groupBy(ReactionDetails::getBaseEmoji)
                                                          .sorted(this::compareReactions)
                                                          .map(entry -> new EmojiCount(entry.getKey(),
                                                                                       getCountDisplayEmoji(entry.getValue()),
                                                                                       entry.getValue()))
                                                          .toList())
                     .observeOn(AndroidSchedulers.mainThread());
  }

  private int compareReactions(@NonNull Map.Entry<String, List<ReactionDetails>> lhs, @NonNull Map.Entry<String, List<ReactionDetails>> rhs) {
    int lengthComparison = -Integer.compare(lhs.getValue().size(), rhs.getValue().size());
    if (lengthComparison != 0) return lengthComparison;

    long latestTimestampLhs = getLatestTimestamp(lhs.getValue());
    long latestTimestampRhs = getLatestTimestamp(rhs.getValue());

    return -Long.compare(latestTimestampLhs, latestTimestampRhs);
  }

  private long getLatestTimestamp(List<ReactionDetails> reactions) {
    return Stream.of(reactions)
                 .max(Comparator.comparingLong(ReactionDetails::getTimestamp))
                 .map(ReactionDetails::getTimestamp)
                 .orElse(-1L);
  }

  private @NonNull String getCountDisplayEmoji(@NonNull List<ReactionDetails> reactions) {
    for (ReactionDetails reaction : reactions) {
      if (reaction.getSender().isLocalNumber()) {
        return reaction.getDisplayEmoji();
      }
    }

    return reactions.get(reactions.size() - 1).getDisplayEmoji();
  }

  @AssistedFactory
  public interface Factory {

    ReactionsViewModel create(@NonNull MessageId messageId);
  }
}
