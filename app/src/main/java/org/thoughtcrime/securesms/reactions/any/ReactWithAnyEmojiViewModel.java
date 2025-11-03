package org.thoughtcrime.securesms.reactions.any;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModel;

import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.components.emoji.EmojiPageModel;
import org.thoughtcrime.securesms.components.emoji.EmojiPageViewGridAdapter;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.keyboard.emoji.search.EmojiSearchRepository;
import org.thoughtcrime.securesms.reactions.ReactionsRepository;
import org.thoughtcrime.securesms.util.adapter.mapping.MappingModelList;

import java.util.List;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;
import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.subjects.BehaviorSubject;

@HiltViewModel(assistedFactory = ReactWithAnyEmojiViewModel.Factory.class)
public final class ReactWithAnyEmojiViewModel extends ViewModel {

  private static final int SEARCH_LIMIT = 40;
  private final EmojiSearchRepository emojiSearchRepository;

  private final ReactWithAnyEmojiRepository  repository;
  private final Observable<MappingModelList> emojiList;
  private final BehaviorSubject<EmojiSearchResult> searchResults;

  @AssistedInject
  public ReactWithAnyEmojiViewModel(
          @Assisted @NonNull MessageId messageId,
          @ApplicationContext Context context,
          @NonNull EmojiSearchRepository emojiSearchRepository,
          @NonNull ReactionsRepository reactionsRepository)
  {
    this.repository            = new ReactWithAnyEmojiRepository(context);
    this.emojiSearchRepository = emojiSearchRepository;
    this.searchResults         = BehaviorSubject.createDefault(new EmojiSearchResult());

    Observable<List<ReactWithAnyEmojiPage>> emojiPages = reactionsRepository.getReactions(messageId)
                                                                                  .map(thisMessagesReactions -> repository.getEmojiPageModels());

    Observable<MappingModelList> emojiList = emojiPages.map(pages -> {
      MappingModelList list = new MappingModelList();

      for (ReactWithAnyEmojiPage page : pages) {
        String key = page.getKey();
        for (ReactWithAnyEmojiPageBlock block : page.getPageBlocks()) {
          list.add(new EmojiPageViewGridAdapter.EmojiHeader(key, block.getLabel()));
          list.addAll(toMappingModels(block.getPageModel()));
        }
      }

      return list;
    });

    this.emojiList = Observable.combineLatest(emojiList, searchResults.distinctUntilChanged(), (all, search) -> {
      if (search.query.isEmpty()) {
        return all;
      } else {
        if (search.model.getDisplayEmoji().isEmpty()) {
          return MappingModelList.singleton(new EmojiPageViewGridAdapter.EmojiNoResultsModel());
        }
        return toMappingModels(search.model);
      }
    });
  }

  @NonNull Observable<MappingModelList> getEmojiList() {
    return emojiList.observeOn(AndroidSchedulers.mainThread());
  }

  void onEmojiSelected(@NonNull String emoji) {
    repository.addEmojiToMessage(emoji);
  }

  public void onQueryChanged(String query) {
    emojiSearchRepository.submitQuery(query, SEARCH_LIMIT, m -> searchResults.onNext(new EmojiSearchResult(query, m)));
  }

  private static @NonNull MappingModelList toMappingModels(@NonNull EmojiPageModel model) {
    return Stream.of(model.getDisplayEmoji())
                .map(e -> new EmojiPageViewGridAdapter.EmojiModel(model.getKey(), e))
                .collect(MappingModelList.collect());
  }

  private static class EmojiSearchResult {
    private final String         query;
    private final EmojiPageModel model;

    private EmojiSearchResult(@NonNull String query, @Nullable EmojiPageModel model) {
      this.query = query;
      this.model = model;
    }

    public EmojiSearchResult() {
      this("", null);
    }
  }

  @AssistedFactory
  public interface Factory {
    ReactWithAnyEmojiViewModel create(@NonNull MessageId messageId);
  }

}
