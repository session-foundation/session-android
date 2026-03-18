package org.thoughtcrime.securesms.database.loaders;


import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;

import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId;
import org.session.libsession.utilities.Address;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.MediaDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabaseExtKt;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.util.AsyncLoader;

public class PagingMediaLoader extends AsyncLoader<Pair<Cursor, Integer>> {

  @SuppressWarnings("unused")
  private static final String TAG = PagingMediaLoader.class.getSimpleName();

  private final Address.Conversable recipient;
  private final Uri       uri;
  private final boolean   leftIsRecent;

  @NonNull private final ThreadDatabase threadDatabase;
  @NonNull private final MediaDatabase mediaDatabase;

  public PagingMediaLoader(@NonNull Context context,
                           @NonNull Address.Conversable recipient,
                           @NonNull Uri uri,
                           boolean leftIsRecent,
                           @NonNull ThreadDatabase threadDatabase,
                           @NonNull MediaDatabase mediaDatabase) {
    super(context);
    this.recipient    = recipient;
    this.uri          = uri;
    this.leftIsRecent = leftIsRecent;
    this.threadDatabase = threadDatabase;
    this.mediaDatabase = mediaDatabase;
  }

  @Nullable
  @Override
  public Pair<Cursor, Integer> loadInBackground() {
    long   threadId = ThreadDatabaseExtKt.getOrCreateThreadIdFor(threadDatabase, recipient);
    Cursor cursor   = mediaDatabase.getGalleryMediaForThread(threadId);

    while (cursor != null && cursor.moveToNext()) {
      AttachmentId attachmentId  = new AttachmentId(cursor.getLong(cursor.getColumnIndexOrThrow(AttachmentDatabase.ROW_ID)), cursor.getLong(cursor.getColumnIndexOrThrow(AttachmentDatabase.UNIQUE_ID)));
      Uri          attachmentUri = PartAuthority.getAttachmentDataUri(attachmentId);

      if (attachmentUri.equals(uri)) {
        return new Pair<>(cursor, leftIsRecent ? cursor.getPosition() : cursor.getCount() - 1 - cursor.getPosition());
      }
    }

    if (cursor != null) {
      cursor.close();
    }
    return null;
  }
}
