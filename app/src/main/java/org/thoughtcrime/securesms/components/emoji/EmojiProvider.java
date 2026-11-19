package org.thoughtcrime.securesms.components.emoji;

import static org.session.libsession.utilities.Util.runOnMain;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.session.libsession.utilities.FutureTaskListener;
import org.session.libsession.utilities.TextSecurePreferences;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.components.emoji.parsing.EmojiDrawInfo;
import org.thoughtcrime.securesms.components.emoji.parsing.EmojiParser;
import org.thoughtcrime.securesms.emoji.EmojiPageCache;
import org.thoughtcrime.securesms.emoji.EmojiSource;
import org.thoughtcrime.securesms.util.Util;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class EmojiProvider {

  private static final    String TAG   = Log.tag(EmojiProvider.class);
  private static final    Paint  PAINT = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

  public static @Nullable EmojiParser.CandidateList getCandidates(@Nullable CharSequence text) {
    if (text == null) return null;
    return new EmojiParser(EmojiSource.getLatest().getEmojiTree()).findCandidates(text);
  }

  static  @Nullable Spannable emojify(@Nullable CharSequence text, @NonNull TextView tv, boolean jumboEmoji) {
    if (tv.isInEditMode()) {
      return null;
    } else {
      return emojify(getCandidates(text), text, tv, jumboEmoji);
    }
  }

  static @Nullable Spannable emojify(@Nullable EmojiParser.CandidateList matches,
                                     @Nullable CharSequence text,
                                     @NonNull TextView tv,
                                     boolean jumboEmoji)
  {
    if (matches == null || text == null || tv.isInEditMode() || TextSecurePreferences.isUseSystemEmojiEnabled(tv.getContext())) return null;
    SpannableStringBuilder builder = new SpannableStringBuilder(text);

    for (EmojiParser.Candidate candidate : matches) {
      Drawable drawable = getEmojiDrawable(tv.getContext(), candidate.getDrawInfo(), tv::requestLayout, jumboEmoji);

      if (drawable != null) {
        builder.setSpan(new EmojiSpan(drawable, tv), candidate.getStartIndex(), candidate.getEndIndex(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    }

    return builder;
  }

  static @Nullable Drawable getEmojiDrawable(@NonNull Context context, @Nullable CharSequence emoji) {
    return getEmojiDrawable(context, emoji, false);
  }

  static @Nullable Drawable getEmojiDrawable(@NonNull Context context, @Nullable CharSequence emoji, boolean jumboEmoji) {
    if (TextUtils.isEmpty(emoji)) {
      return null;
    }

    if (TextSecurePreferences.isUseSystemEmojiEnabled(context)) {
      return new SystemEmojiDrawable(context, emoji.toString());
    }

    EmojiDrawInfo drawInfo = EmojiSource.getLatest().getEmojiTree().getEmoji(emoji, 0, emoji.length());
    return getEmojiDrawable(context, drawInfo, null, jumboEmoji);
  }

  /**
   * Gets an EmojiDrawable from the Page Cache
   *
   * @param context         Context object used in reading and writing from disk
   * @param drawInfo        Information about the emoji being displayed
   * @param onEmojiLoaded   Runnable which will trigger when an emoji is loaded from disk
   */
  private static @Nullable Drawable getEmojiDrawable(@NonNull Context context, @Nullable EmojiDrawInfo drawInfo, @Nullable Runnable onEmojiLoaded, boolean jumboEmoji) {
    if (drawInfo == null) {
      return null;
    }

    final int           lowMemoryDecodeScale = Util.isLowMemory(context) ? 2 : 1;
    final EmojiSource   source               = EmojiSource.getLatest();
    final EmojiDrawable drawable             = new EmojiDrawable(source, drawInfo, lowMemoryDecodeScale);
    final AtomicBoolean jumboLoaded          = new AtomicBoolean(false);

    EmojiPageCache.LoadResult loadResult = EmojiPageCache.INSTANCE.load(context, drawInfo.getPage(), lowMemoryDecodeScale);

    if (loadResult instanceof EmojiPageCache.LoadResult.Immediate) {
      runOnMain(() -> drawable.setBitmap(((EmojiPageCache.LoadResult.Immediate) loadResult).getBitmap()));
    } else if (loadResult instanceof EmojiPageCache.LoadResult.Async) {
      ((EmojiPageCache.LoadResult.Async) loadResult).getTask().addListener(new FutureTaskListener<Bitmap>() {
        @Override
        public void onSuccess(Bitmap result) {
          runOnMain(() -> {
            if (!jumboLoaded.get()) {
              drawable.setBitmap(result);
              if (onEmojiLoaded != null) {
                onEmojiLoaded.run();
              }
            }
          });
        }

        @Override
        public void onFailure(ExecutionException exception) {
          Log.d(TAG, "Failed to load emoji bitmap resource", exception);
        }
      });
    } else {
      throw new IllegalStateException("Unexpected subclass " + loadResult.getClass());
    }

    return drawable;
  }

  static final class EmojiDrawable extends Drawable {
    private final float intrinsicWidth;
    private final float intrinsicHeight;
    private final Rect  emojiBounds;

    private Bitmap  bmp;
    private boolean isSingleBitmap;

    @Override
    public int getIntrinsicWidth() {
      return (int) intrinsicWidth;
    }

    @Override
    public int getIntrinsicHeight() {
      return (int) intrinsicHeight;
    }

    EmojiDrawable(@NonNull EmojiSource source, @NonNull EmojiDrawInfo info, int lowMemoryDecodeScale) {
      this.intrinsicWidth  = (source.getMetrics().getRawWidth() * source.getDecodeScale()) / lowMemoryDecodeScale;
      this.intrinsicHeight = (source.getMetrics().getRawHeight() * source.getDecodeScale()) / lowMemoryDecodeScale;

      final int glyphWidth  = (int) (intrinsicWidth);
      final int glyphHeight = (int) (intrinsicHeight);
      final int index       = info.getIndex();
      final int emojiPerRow = source.getMetrics().getPerRow();
      final int xStart      = (index % emojiPerRow) * glyphWidth;
      final int yStart      = (index / emojiPerRow) * glyphHeight;

      this.emojiBounds = new Rect(xStart + 1,
              yStart + 1,
              xStart + glyphWidth - 1,
              yStart + glyphHeight - 1);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
      if (bmp == null) {
        return;
      }

      canvas.drawBitmap(bmp,
              isSingleBitmap ? null : emojiBounds,
              getBounds(),
              PAINT);
    }

    public void setBitmap(Bitmap bitmap) {
      setBitmap(bitmap, false);
    }

    public void setSingleBitmap(Bitmap bitmap) {
      setBitmap(bitmap, true);
    }

    private void setBitmap(Bitmap bitmap, boolean isSingleBitmap) {
      this.isSingleBitmap = isSingleBitmap;
      if (bmp == null || !bmp.sameAs(bitmap)) {
        bmp = bitmap;
        invalidateSelf();
      }
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void setAlpha(int alpha) { }

    @Override
    public void setColorFilter(ColorFilter cf) { }
  }

  /**
   * Renders a single emoji using the device's native system font at full vector resolution.
   */
  static final class SystemEmojiDrawable extends Drawable {
    private final String    emoji;
    private final int       intrinsicSize;
    private final TextPaint textPaint  = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect      textBounds = new Rect();

    SystemEmojiDrawable(@NonNull Context context, @NonNull String emoji) {
      this.emoji         = emoji;
      this.intrinsicSize = Math.round(32f * context.getResources().getDisplayMetrics().density);
      this.textPaint.setTextAlign(Paint.Align.LEFT);
    }

    @Override
    public int getIntrinsicWidth() {
      return intrinsicSize;
    }

    @Override
    public int getIntrinsicHeight() {
      return intrinsicSize;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
      final Rect bounds = getBounds();
      final int  width  = bounds.width();
      final int  height = bounds.height();
      if (width <= 0 || height <= 0) {
        return;
      }

      final int targetSize = Math.min(width, height);
      float     textSize   = targetSize * 0.85f;
      textPaint.setTextSize(textSize);
      textPaint.getTextBounds(emoji, 0, emoji.length(), textBounds);

      final float maxAllowed = targetSize * 0.90f;
      if (textBounds.width() > maxAllowed || textBounds.height() > maxAllowed) {
        final float maxDim = Math.max(textBounds.width(), textBounds.height());
        if (maxDim > 0) {
          textSize *= maxAllowed / maxDim;
          textPaint.setTextSize(textSize);
          textPaint.getTextBounds(emoji, 0, emoji.length(), textBounds);
        }
      }

      final float x = bounds.exactCenterX() - textBounds.exactCenterX();
      final float y = !textBounds.isEmpty()
              ? bounds.exactCenterY() - textBounds.exactCenterY()
              : bounds.exactCenterY() - ((textPaint.ascent() + textPaint.descent()) / 2f);
      canvas.drawText(emoji, 0, emoji.length(), x, y, textPaint);
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void setAlpha(int alpha) {
      textPaint.setAlpha(alpha);
      invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter cf) {
      textPaint.setColorFilter(cf);
      invalidateSelf();
    }
  }

}
