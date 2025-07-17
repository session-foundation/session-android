package org.thoughtcrime.securesms.database;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import network.loki.messenger.BuildConfig;

/**
 * Starting in API 26, a {@link ContentProvider} needs to be defined for each authority you wish to
 * observe changes on. These classes essentially do nothing except exist so Android doesn't complain.
 */
public class DatabaseContentProviders {

  public static class ConversationList extends NoopContentProvider {
    public static final Uri CONTENT_URI = Uri.parse("content://network.loki.securesms.database.conversationlist" + BuildConfig.AUTHORITY_POSTFIX);
  }

  public static class Conversation extends NoopContentProvider {
    public static final Uri CONTENT_URI = Uri.parse("content://network.loki.securesms.database.conversation" + BuildConfig.AUTHORITY_POSTFIX);

    public static Uri getUriForThread(long threadId) {
      return CONTENT_URI.buildUpon()
              .appendPath(String.valueOf(threadId))
              .build();
    }
  }

  public static class Attachment extends NoopContentProvider {
    public static final Uri CONTENT_URI = Uri.parse("content://network.loki.securesms.database.attachment" + BuildConfig.AUTHORITY_POSTFIX);
  }

  public static class Sticker extends NoopContentProvider {
    public static final Uri CONTENT_URI = Uri.parse("content://network.loki.securesms.database.sticker" + BuildConfig.AUTHORITY_POSTFIX);
  }

  public static class StickerPack extends NoopContentProvider {
    public static final Uri CONTENT_URI = Uri.parse("content://network.loki.securesms.database.stickerpack" + BuildConfig.AUTHORITY_POSTFIX);
  }

  public static class Recipient extends NoopContentProvider {
    public static final Uri CONTENT_URI = Uri.parse("content://network.loki.securesms.database.recipient" + BuildConfig.AUTHORITY_POSTFIX);
  }

  private static abstract class NoopContentProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
      return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
      return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
      return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
      return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
      return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
      return 0;
    }
  }
}
