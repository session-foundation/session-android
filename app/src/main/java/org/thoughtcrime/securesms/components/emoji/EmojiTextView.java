package org.thoughtcrime.securesms.components.emoji;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.TextViewCompat;
import androidx.appcompat.widget.AppCompatTextView;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import network.loki.messenger.R;
import org.thoughtcrime.securesms.components.emoji.EmojiProvider.EmojiDrawable;
import org.thoughtcrime.securesms.components.emoji.parsing.EmojiParser;
import org.session.libsession.utilities.Util;
import org.session.libsignal.utilities.guava.Optional;

public class EmojiTextView extends AppCompatTextView {
  private final boolean scaleEmojis;

  private CharSequence previousText;
  private BufferType   previousBufferType = BufferType.NORMAL;
  private float        originalFontSize;
  private boolean      sizeChangeInProgress;
  private CharSequence overflowText;
  private CharSequence previousOverflowText;

  public EmojiTextView(Context context) {
    this(context, null);
  }

  public EmojiTextView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public EmojiTextView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    scaleEmojis = true;
    originalFontSize = getResources().getDimension(R.dimen.medium_font_size);
  }

  @Override public void setText(@Nullable CharSequence text, BufferType type) {
    // No need to do anything special if the text is null or empty
    if (text == null || text.length() == 0) {
      previousText         = text;
      previousOverflowText = overflowText;
      previousBufferType   = type;
      super.setText(text, type);
      return;
    }

    EmojiParser.CandidateList candidates = EmojiProvider.getCandidates(text);

    if (scaleEmojis && candidates != null && candidates.allEmojis) {
      int   emojis = candidates.size();
      float scale  = 1.0f;

      if (emojis <= 8) scale += 0.25f;
      if (emojis <= 6) scale += 0.25f;
      if (emojis <= 4) scale += 0.25f;
      if (emojis <= 2) scale += 0.25f;

      super.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalFontSize * scale);
    } else if (scaleEmojis) {
      super.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalFontSize);
    }

    if (unchanged(text, overflowText, type)) {
      return;
    }

    previousText         = text;
    previousOverflowText = overflowText;
    previousBufferType   = type;

    if (candidates == null || candidates.size() == 0) {
      super.setText(new SpannableStringBuilder(Optional.fromNullable(text).or("")).append(Optional.fromNullable(overflowText).or("")), BufferType.NORMAL);
    } else {
      CharSequence emojified = EmojiProvider.emojify(candidates, text, this, false);
      super.setText(new SpannableStringBuilder(emojified).append(Optional.fromNullable(overflowText).or("")), BufferType.SPANNABLE);
    }
  }

  private boolean unchanged(CharSequence text, CharSequence overflowText, BufferType bufferType) {
    CharSequence finalPrevText = (previousText == null || previousText.length() == 0 ? "" : previousText);
    CharSequence finalText = (text == null || text.length() == 0 ? "" : text);
    CharSequence finalPrevOverflowText = (previousOverflowText == null || previousOverflowText.length() == 0 ? "" : previousOverflowText);
    CharSequence finalOverflowText = (overflowText == null || overflowText.length() == 0 ? "" : overflowText);

    return Util.equals(finalPrevText, finalText)                 &&
           Util.equals(finalPrevOverflowText, finalOverflowText) &&
           Util.equals(previousBufferType, bufferType)           &&
           !sizeChangeInProgress;
  }


  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);

    if (!sizeChangeInProgress) {
      sizeChangeInProgress = true;
      setText(previousText, previousBufferType);
      sizeChangeInProgress = false;
    }
  }

  @Override
  public void invalidateDrawable(@NonNull Drawable drawable) {
    if (drawable instanceof EmojiDrawable) {
      invalidate();
    } else {
      super.invalidateDrawable(drawable);
    }
  }

  @Override
  public void setTextSize(float size) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
  }

  @Override
  public void setTextSize(int unit, float size) {
    this.originalFontSize = TypedValue.applyDimension(unit, size, getResources().getDisplayMetrics());
    super.setTextSize(unit, size);
  }
}
