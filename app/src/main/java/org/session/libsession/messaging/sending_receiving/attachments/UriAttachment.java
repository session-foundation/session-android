package org.session.libsession.messaging.sending_receiving.attachments;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class UriAttachment extends Attachment {

  private final @NonNull  Uri dataUri;
  private final @Nullable Uri thumbnailUri;

  public UriAttachment(@NonNull Uri uri, @NonNull String contentType, int transferState, long size,
                       @Nullable String fileName, boolean voiceNote, boolean quote, @Nullable String caption)
  {
    this(uri, uri, contentType, transferState, size, 0, 0, fileName, null, voiceNote, quote, caption, -1);
  }

  public UriAttachment(@NonNull Uri dataUri, @Nullable Uri thumbnailUri,
                       @NonNull String contentType, int transferState, long size, int width, int height,
                       @Nullable String fileName, @Nullable String fastPreflightId,
                       boolean voiceNote, boolean quote, @Nullable String caption) {
    this(dataUri, thumbnailUri, contentType, transferState, size, width, height, fileName, fastPreflightId,
         voiceNote, quote, caption, -1);
  }

  public UriAttachment(@NonNull Uri dataUri, @Nullable Uri thumbnailUri,
                       @NonNull String contentType, int transferState, long size, int width, int height,
                       @Nullable String fileName, @Nullable String fastPreflightId,
                       boolean voiceNote, boolean quote, @Nullable String caption, long audioDurationMs)
  {
    super(contentType, transferState, size, fileName, null, null, null, null, fastPreflightId, voiceNote, width, height, quote, caption, "", audioDurationMs);
    this.dataUri      = dataUri;
    this.thumbnailUri = thumbnailUri;
  }

  @Override
  @NonNull
  public Uri getDataUri() {
    return dataUri;
  }

  @Override
  @Nullable
  public Uri getThumbnailUri() {
    return thumbnailUri;
  }

  @Override
  public boolean equals(Object other) {
    return other != null && other instanceof UriAttachment && ((UriAttachment) other).dataUri.equals(this.dataUri);
  }

  @Override
  public int hashCode() {
    return dataUri.hashCode();
  }
}
