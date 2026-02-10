package org.thoughtcrime.securesms.components.emoji;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.util.AttributeSet;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import org.session.libsession.utilities.Util;
import org.session.libsignal.utilities.guava.Optional;
import org.thoughtcrime.securesms.util.EmojiUtils;

import network.loki.messenger.R;

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

  public EmojiTextView(Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public EmojiTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    scaleEmojis = true;
    originalFontSize = getResources().getDimension(R.dimen.medium_font_size);
  }

  @Override
  public void setText(@Nullable CharSequence text, BufferType type) {
    if (text == null || text.length() == 0) {
      previousText = text;
      previousOverflowText = overflowText;
      previousBufferType = type;
      super.setText(text, type);
      return;
    }

    if (unchanged(text, overflowText, type)) {
      return;
    }

    if(scaleEmojis) {
      int emojiCount = EmojiUtils.INSTANCE.getOnlyEmojiCount(text);
      if(emojiCount > 0){
        float scale = 1.0f;
        if (emojiCount <= 8) scale += 0.35f;
        if (emojiCount <= 6) scale += 0.35f;
        if (emojiCount <= 4) scale += 0.35f;
        if (emojiCount <= 2) scale += 0.35f;
        if (emojiCount <= 1) scale += 0.35f;
        super.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalFontSize * scale);
      } else {
        super.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalFontSize);
      }
    } else {
      super.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalFontSize);
    }

    previousText         = text;
    previousOverflowText = overflowText;
    previousBufferType   = type;

    // Let EmojiCompat (already initialized elsewhere) do its work.
    SpannableStringBuilder builder = new SpannableStringBuilder();
    builder.append(text).append(Optional.fromNullable(overflowText).or(""));
    super.setText(builder, BufferType.SPANNABLE);
  }

  private boolean unchanged(CharSequence text, CharSequence overflowText, BufferType bufferType) {
    CharSequence finalPrevText = (previousText == null || previousText.length() == 0 ? "" : previousText);
    CharSequence finalText = (text == null || text.length() == 0 ? "" : text);
    CharSequence finalPrevOverflowText = (previousOverflowText == null || previousOverflowText.length() == 0 ? "" : previousOverflowText);
    CharSequence finalOverflowText = (overflowText == null || overflowText.length() == 0 ? "" : overflowText);
    return Util.equals(finalPrevText, finalText)
            && Util.equals(finalPrevOverflowText, finalOverflowText)
            && Util.equals(previousBufferType, bufferType)
            && !sizeChangeInProgress;
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
    super.invalidateDrawable(drawable);
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
