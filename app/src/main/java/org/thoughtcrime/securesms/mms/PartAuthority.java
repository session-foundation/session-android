package org.thoughtcrime.securesms.mms;

import android.content.ContentUris;
import android.content.Context;
import android.content.UriMatcher;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import org.session.libsession.messaging.sending_receiving.attachments.Attachment;
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId;
import org.thoughtcrime.securesms.dependencies.DatabaseComponent;
import org.thoughtcrime.securesms.providers.BlobUtils;
import org.thoughtcrime.securesms.providers.PartAndBlobProvider;

public class PartAuthority {

  private static final String PART_URI_STRING     = "content://network.loki.provider.securesms/part";
  private static final String THUMB_URI_STRING    = "content://network.loki.provider.securesms/thumb";
  private static final String STICKER_URI_STRING  = "content://network.loki.provider.securesms/sticker";
  private static final Uri    PART_CONTENT_URI    = Uri.parse(PART_URI_STRING);
  private static final Uri    THUMB_CONTENT_URI   = Uri.parse(THUMB_URI_STRING);
  private static final Uri    STICKER_CONTENT_URI = Uri.parse(STICKER_URI_STRING);

  private static final int PART_ROW       = 1;
  private static final int THUMB_ROW      = 2;
  private static final int PERSISTENT_ROW = 3;
  private static final int BLOB_ROW       = 4;
  private static final int STICKER_ROW    = 5;

  private static final UriMatcher uriMatcher;

  static {
    uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    uriMatcher.addURI("network.loki.provider.securesms", "part/*/#", PART_ROW);
    uriMatcher.addURI("network.loki.provider.securesms", "thumb/*/#", THUMB_ROW);
    uriMatcher.addURI("network.loki.provider.securesms", "sticker/#", STICKER_ROW);
    uriMatcher.addURI(BlobUtils.AUTHORITY, BlobUtils.PATH, BLOB_ROW);
  }

  public static InputStream getAttachmentStream(@NonNull Context context, @NonNull Uri uri)
      throws IOException
  {
    int match = uriMatcher.match(uri);
    try {
      switch (match) {
      case PART_ROW:       return DatabaseComponent.get(context).attachmentDatabase().getAttachmentStream(new PartUriParser(uri).getPartId(), 0);
      case THUMB_ROW:      return DatabaseComponent.get(context).attachmentDatabase().getThumbnailStream(new PartUriParser(uri).getPartId());
      case BLOB_ROW:       return BlobUtils.getInstance().getStream(context, uri);
      default:             return context.getContentResolver().openInputStream(uri);
      }
    } catch (SecurityException se) {
      throw new IOException(se);
    }
  }

  public static @Nullable String getAttachmentFileName(@NonNull Context context, @NonNull Uri uri) {
    int match = uriMatcher.match(uri);

    switch (match) {
    case THUMB_ROW:
    case PART_ROW:
      Attachment attachment = DatabaseComponent.get(context).attachmentDatabase().getAttachment(new PartUriParser(uri).getPartId());

      if (attachment != null) return attachment.getFilename();
      else                    return null;
    case BLOB_ROW:
      return BlobUtils.getFileName(uri);
    default:
      return null;
    }
  }

  public static @Nullable Long getAttachmentSize(@NonNull Context context, @NonNull Uri uri) {
    int match = uriMatcher.match(uri);

    switch (match) {
      case THUMB_ROW:
      case PART_ROW:
        Attachment attachment = DatabaseComponent.get(context).attachmentDatabase().getAttachment(new PartUriParser(uri).getPartId());

        if (attachment != null) return attachment.getSize();
        else                    return null;
      case BLOB_ROW:
        return BlobUtils.getFileSize(uri);
      default:
        return null;
    }
  }

  public static @Nullable String getAttachmentContentType(@NonNull Context context, @NonNull Uri uri) {
    int match = uriMatcher.match(uri);

    switch (match) {
      case THUMB_ROW:
      case PART_ROW:
        Attachment attachment = DatabaseComponent.get(context).attachmentDatabase().getAttachment(new PartUriParser(uri).getPartId());

        if (attachment != null) return attachment.getContentType();
        else                    return null;
      case BLOB_ROW:
        return BlobUtils.getMimeType(uri);
      default:
        return null;
    }
  }

  public static Uri getAttachmentPublicUri(Uri uri) {
    PartUriParser partUri = new PartUriParser(uri);
    return PartAndBlobProvider.getContentUri(partUri.getPartId());
  }

  public static Uri getAttachmentDataUri(AttachmentId attachmentId) {
    Uri uri = Uri.withAppendedPath(PART_CONTENT_URI, String.valueOf(attachmentId.getUniqueId()));
    return ContentUris.withAppendedId(uri, attachmentId.getRowId());
  }

  public static Uri getAttachmentThumbnailUri(AttachmentId attachmentId) {
    Uri uri = Uri.withAppendedPath(THUMB_CONTENT_URI, String.valueOf(attachmentId.getUniqueId()));
    return ContentUris.withAppendedId(uri, attachmentId.getRowId());
  }

  public static boolean isLocalUri(final @NonNull Uri uri) {
    int match = uriMatcher.match(uri);
    switch (match) {
    case PART_ROW:
    case THUMB_ROW:
    case PERSISTENT_ROW:
    case BLOB_ROW:
      return true;
    }
    return false;
  }
}
