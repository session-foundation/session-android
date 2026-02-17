package org.thoughtcrime.securesms.reactions.any;

import android.content.Context;

import androidx.annotation.NonNull;

import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.components.emoji.EmojiPageModel;
import org.thoughtcrime.securesms.components.emoji.RecentEmojiPageModel;
import org.thoughtcrime.securesms.emoji.EmojiCategory;
import org.thoughtcrime.securesms.emoji.EmojiSource;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;

import network.loki.messenger.R;

public final class ReactWithAnyEmojiRepository {

  private static final String TAG = Log.tag(ReactWithAnyEmojiRepository.class);

  private final RecentEmojiPageModel recentEmojiPageModel;
  private final List<ReactWithAnyEmojiPage> emojiPages;

  @Inject
  ReactWithAnyEmojiRepository(RecentEmojiPageModel recentEmojiPageModel) {
    this.recentEmojiPageModel = recentEmojiPageModel;
    this.emojiPages           = new LinkedList<>();

      for (EmojiPageModel page : EmojiSource.getLatest().getDisplayPages()) {
          emojiPages.add(
                  new ReactWithAnyEmojiPage(
                          Collections.singletonList(
                                  new ReactWithAnyEmojiPageBlock(
                                          EmojiCategory.getCategoryLabel(page.getIconAttr()),
                                          page
                                  )
                          )
                  )
          );
      }
  }

  List<ReactWithAnyEmojiPage> getEmojiPageModels() {
    List<ReactWithAnyEmojiPage> pages       = new LinkedList<>();

    pages.add(new ReactWithAnyEmojiPage(Collections.singletonList(new ReactWithAnyEmojiPageBlock(R.string.emojiCategoryRecentlyUsed, recentEmojiPageModel))));
    pages.addAll(emojiPages);

    return pages;
  }

  void addEmojiToMessage(@NonNull String emoji) {
    recentEmojiPageModel.onEmojiUsed(emoji);
  }
}
