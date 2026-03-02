package org.thoughtcrime.securesms.notifications;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.Action;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import org.session.libsession.utilities.NotificationPrivacyPreference;
import org.session.libsession.utilities.Util;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsession.utilities.recipients.RecipientNamesKt;
import org.session.libsession.utilities.recipients.RemoteFile;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.mms.SlideDeck;
import org.thoughtcrime.securesms.util.AvatarUtils;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.inject.Provider;

import coil3.BitmapImage;
import coil3.Image;
import coil3.ImageLoader;
import coil3.ImageLoaders;
import coil3.request.CachePolicy;
import coil3.request.ImageRequest;
import coil3.request.ImageResult;
import network.loki.messenger.R;

public class SingleRecipientNotificationBuilder extends AbstractNotificationBuilder {

  private static final String TAG = SingleRecipientNotificationBuilder.class.getSimpleName();

  private final List<CharSequence> messageBodies = new LinkedList<>();

  private SlideDeck    slideDeck;
  private CharSequence contentTitle;
  private CharSequence contentText;
  private AvatarUtils avatarUtils;
  private final Provider<ImageLoader> imageLoaderProvider;

  private static final Integer ICON_SIZE = 128;

  public SingleRecipientNotificationBuilder(
          @NonNull Context context,
          @NonNull NotificationPrivacyPreference privacy,
          @NonNull AvatarUtils avatarUtils,
          Provider<ImageLoader> imageLoaderProvider
  ) {
    super(context, privacy);

    this.avatarUtils = avatarUtils;
    this.imageLoaderProvider = imageLoaderProvider;
    setSmallIcon(R.drawable.ic_notification);
    setColor(ContextCompat.getColor(context, R.color.accent_green));
    setCategory(NotificationCompat.CATEGORY_MESSAGE);
  }

  public void setThread(@NonNull Recipient recipient) {
    setChannelId(NotificationChannels.getMessagesChannel(context));

    Bitmap largeIconBitmap;
    boolean recycleBitmap;

    if (privacy.isDisplayContact()) {
      setContentTitle(RecipientNamesKt.displayName(recipient));

      RemoteFile avatar = recipient.getAvatar();
      if (avatar != null) {
        try {
          int iconWidth = context.getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_width);
          int iconHeight = context.getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_height);

          final ImageResult result = ImageLoaders.executeBlocking(
                  imageLoaderProvider.get(),
                  new ImageRequest.Builder(context)
                          .data(avatar)
                          .size(iconWidth, iconHeight)
                          .networkCachePolicy(CachePolicy.DISABLED)
                          .fallback(new BitmapImage(getPlaceholderDrawable(avatarUtils, recipient), true))
                          .build()
          );

          Image image = result.getImage();

          if (image instanceof BitmapImage) {
            largeIconBitmap = ((BitmapImage) image).getBitmap();
            recycleBitmap = false;
          } else if (image != null) {
            // Generate a bitmap from this generic image by drawing on the bitmap
            largeIconBitmap = Bitmap.createBitmap(iconWidth, iconHeight, Bitmap.Config.RGB_565);
            image.draw(new Canvas(largeIconBitmap));
            recycleBitmap = true;
          } else {
            throw new IllegalStateException("No image returned from Coil");
          }

        } catch (Exception e) {
          Log.w(TAG, "get iconBitmap in getThread failed", e);
          largeIconBitmap = getPlaceholderDrawable(avatarUtils, recipient);
          recycleBitmap = true;
        }
      } else {
        largeIconBitmap = getPlaceholderDrawable(avatarUtils, recipient);
        recycleBitmap = true;
      }

      setLargeIcon(getCircularBitmap(largeIconBitmap));
      if(recycleBitmap) largeIconBitmap.recycle();

    } else {
      setContentTitle(context.getString(R.string.app_name));

      Drawable drawable = ContextCompat.getDrawable(context, R.drawable.ic_user_filled_custom_padded);
      int iconWidth  = context.getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_width);
      int iconHeight = context.getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_height);

      Bitmap src = Bitmap.createBitmap(iconWidth, iconHeight, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(src);
      canvas.drawColor(context.getColor(R.color.classic_dark_3));

      int padding = (int) (iconWidth * 0.08); //add some padding to the icon
      drawable.setBounds(padding, padding, iconWidth - padding, iconHeight - padding);
      drawable.draw(canvas);

      setLargeIcon(getCircularBitmap(src));
      setColor(context.getColor(R.color.classic_dark_3));
      src.recycle();
    }
  }

  public void setMessageCount(int messageCount) {
    setContentInfo(String.valueOf(messageCount));
    setNumber(messageCount);
  }

  public void setPrimaryMessageBody(@NonNull Recipient threadRecipient,
                                    @NonNull Recipient individualRecipient,
                                    @NonNull  CharSequence message,
                                    @Nullable SlideDeck slideDeck)
  {
    SpannableStringBuilder stringBuilder = new SpannableStringBuilder();

    if (privacy.isDisplayContact() && threadRecipient.isGroupOrCommunityRecipient()) {
      stringBuilder.append(Util.getBoldedString(RecipientNamesKt.displayName(individualRecipient) + ": "));
    }

    if (privacy.isDisplayMessage()) {
      setContentText(stringBuilder.append(message));
      this.slideDeck = slideDeck;
    } else {
      setContentText(stringBuilder.append(context.getResources().getQuantityString(R.plurals.messageNew, 1, 1)));
    }
  }

  public void addAndroidAutoAction(@NonNull PendingIntent androidAutoReplyIntent,
                                   @NonNull PendingIntent androidAutoHeardIntent, long timestamp)
  {

    if (contentTitle == null || contentText == null)
      return;

    RemoteInput remoteInput = new RemoteInput.Builder(AndroidAutoReplyReceiver.VOICE_REPLY_KEY)
                                  .setLabel(context.getString(R.string.reply))
                                  .build();

    NotificationCompat.CarExtender.UnreadConversation.Builder unreadConversationBuilder =
            new NotificationCompat.CarExtender.UnreadConversation.Builder(contentTitle.toString())
                .addMessage(contentText.toString())
                .setLatestTimestamp(timestamp)
                .setReadPendingIntent(androidAutoHeardIntent)
                .setReplyAction(androidAutoReplyIntent, remoteInput);

    extend(new NotificationCompat.CarExtender().setUnreadConversation(unreadConversationBuilder.build()));
  }

  public void addActions(@NonNull PendingIntent markReadIntent,
                         @Nullable PendingIntent quickReplyIntent,
                         @Nullable PendingIntent wearableReplyIntent,
                         @NonNull ReplyMethod replyMethod)
  {
    Action markAsReadAction = new Action(R.drawable.ic_check,
                                         context.getString(R.string.messageMarkRead),
                                         markReadIntent);

    addAction(markAsReadAction);

    NotificationCompat.WearableExtender wearableExtender = new NotificationCompat.WearableExtender().addAction(markAsReadAction);

    if (quickReplyIntent != null) {
      String actionName = context.getString(R.string.reply);
      String label = context.getString(replyMethodLongDescription(replyMethod));

      Action replyAction = new Action(R.drawable.ic_reply, actionName, quickReplyIntent);

      replyAction = new Action.Builder(R.drawable.ic_reply,
              actionName,
              wearableReplyIntent)
              .addRemoteInput(new RemoteInput.Builder(NotificationProcessor.EXTRA_REMOTE_REPLY).setLabel(label).build())
              .build();

      Action wearableReplyAction = new Action.Builder(R.drawable.ic_reply,
              actionName,
              wearableReplyIntent)
              .addRemoteInput(new RemoteInput.Builder(NotificationProcessor.EXTRA_REMOTE_REPLY).setLabel(label).build())
              .build();


      addAction(replyAction);
      wearableExtender.addAction(wearableReplyAction);
    }

    extend(wearableExtender);
  }

  @StringRes
  private static int replyMethodLongDescription(@NonNull ReplyMethod replyMethod) {
    return R.string.reply;
  }

  public void putStringExtra(String key, String value) {
    extras.putString(key,value);
  }

  public void addMessageBody(@NonNull Recipient threadRecipient,
                             @NonNull Recipient individualRecipient,
                             @Nullable CharSequence messageBody)
  {
    SpannableStringBuilder stringBuilder = new SpannableStringBuilder();

    if (privacy.isDisplayContact() && threadRecipient.isGroupOrCommunityRecipient()) {
      stringBuilder.append(Util.getBoldedString(RecipientNamesKt.displayName(individualRecipient) + ": "));
    }

    if (privacy.isDisplayMessage()) {
      messageBodies.add(stringBuilder.append(messageBody == null ? "" : messageBody));
    } else {
      messageBodies.add(stringBuilder.append(context.getResources().getQuantityString(R.plurals.messageNew, 1, 1)));
    }
  }

  @Override
  public Notification build() {
    if (privacy.isDisplayMessage()) {
      if (messageBodies.size() == 1 && hasBigPictureSlide(slideDeck)) {
        setStyle(new NotificationCompat.BigPictureStyle()
                     .bigPicture(getBigPicture(slideDeck))
                     .setSummaryText(getBigText(messageBodies)));
      } else {
        setStyle(new NotificationCompat.BigTextStyle().bigText(getBigText(messageBodies)));
      }
    }

    return super.build();
  }

  private Bitmap getCircularBitmap(Bitmap bitmap) {
    boolean recycleInputBitmap = false;

    if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
      bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
      recycleInputBitmap = true;
    }

    final Bitmap output = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
    final Canvas canvas = new Canvas(output);
    final int color = Color.RED;
    final Paint paint = new Paint();
    final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
    final RectF rectF = new RectF(rect);

    paint.setAntiAlias(true);
    canvas.drawARGB(0, 0, 0, 0);
    paint.setColor(color);
    canvas.drawOval(rectF, paint);
    paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
    canvas.drawBitmap(bitmap, rect, rect, paint);

    if (recycleInputBitmap) {
      bitmap.recycle();
    }

    return output;
  }

  private boolean hasBigPictureSlide(@Nullable SlideDeck slideDeck) {
    if (slideDeck == null) {
      return false;
    }

    Slide thumbnailSlide = slideDeck.getThumbnailSlide();

    return thumbnailSlide != null         &&
           thumbnailSlide.hasImage()      &&
           !thumbnailSlide.isInProgress() &&
           thumbnailSlide.getThumbnailUri() != null;
  }

  private Bitmap getBigPicture(@NonNull SlideDeck slideDeck)
  {
    try {
      @SuppressWarnings("ConstantConditions")
      Uri uri = slideDeck.getThumbnailSlide().getThumbnailUri();

      return Glide.with(context.getApplicationContext())
                     .asBitmap()
                     .load(new DecryptableStreamUriLoader.DecryptableUri(uri))
                     .diskCacheStrategy(DiskCacheStrategy.NONE)
                     .submit(64, 64)
                     .get();
    } catch (InterruptedException | ExecutionException e) {
      Log.w(TAG, "getBigPicture failed", e);
      return Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565);
    }
  }

  @Override
  public NotificationCompat.Builder setContentTitle(CharSequence contentTitle) {
    this.contentTitle = contentTitle;
    return super.setContentTitle(contentTitle);
  }

  public NotificationCompat.Builder setContentText(CharSequence contentText) {
    this.contentText = trimToDisplayLength(contentText);
    return super.setContentText(this.contentText);
  }

  private CharSequence getBigText(List<CharSequence> messageBodies) {
    SpannableStringBuilder content = new SpannableStringBuilder();

    for (int i = 0; i < messageBodies.size(); i++) {
      content.append(trimToDisplayLength(messageBodies.get(i)));
      if (i < messageBodies.size() - 1) {
        content.append('\n');
      }
    }

    return content;
  }

  private static Bitmap getPlaceholderDrawable(AvatarUtils avatarUtils, Recipient recipient) {
    String publicKey = recipient.getAddress().toString();
    String displayName = RecipientNamesKt.displayName(recipient);
    return avatarUtils.generateTextBitmap(ICON_SIZE, publicKey, displayName);
  }
}
