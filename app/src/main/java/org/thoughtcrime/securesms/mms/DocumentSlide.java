package org.thoughtcrime.securesms.mms;


import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.session.libsession.messaging.sending_receiving.attachments.Attachment;
import org.session.libsignal.utilities.ExternalStorageUtil;

public class DocumentSlide extends Slide {

  public DocumentSlide(@NonNull Context context, @NonNull Attachment attachment) {
    super(context, attachment);
  }

  public DocumentSlide(@NonNull Context context, @NonNull Uri uri, @Nullable String fileName, @NonNull String contentType,  long size) {
    super(context, constructAttachmentFromUri(context, uri, contentType, size, 0, 0, true, ExternalStorageUtil.getCleanFileName(fileName), null, false, false));
  }

  @Override
  public boolean hasDocument() {
    return true;
  }

}
