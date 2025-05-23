package org.thoughtcrime.securesms.attachments;

import android.net.Uri;

import androidx.annotation.Nullable;

import org.session.libsession.messaging.sending_receiving.attachments.Attachment;
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentState;
import org.thoughtcrime.securesms.database.MmsDatabase;

public class MmsNotificationAttachment extends Attachment {

  public MmsNotificationAttachment(int status, long size) {
    super("application/mms", getTransferStateFromStatus(status).getValue(), size, null, null, null, null, null, null, false, 0, 0, false, null, "");
  }

  @Nullable
  @Override
  public Uri getDataUri() { return null; }

  @Nullable
  @Override
  public Uri getThumbnailUri() { return null; }

  private static AttachmentState getTransferStateFromStatus(int status) {
    if (status == MmsDatabase.Status.DOWNLOAD_INITIALIZED ||
        status == MmsDatabase.Status.DOWNLOAD_NO_CONNECTIVITY)
    {
      return AttachmentState.PENDING;
    } else if (status == MmsDatabase.Status.DOWNLOAD_CONNECTING) {
      return AttachmentState.DOWNLOADING;
    } else {
      return AttachmentState.FAILED;
    }
  }
}
