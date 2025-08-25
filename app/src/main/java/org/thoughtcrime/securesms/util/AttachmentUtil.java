package org.thoughtcrime.securesms.util;


import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId;
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.dependencies.DatabaseComponent;

public class AttachmentUtil {

  private static final String TAG = AttachmentUtil.class.getSimpleName();

  /**
   * Deletes the specified attachment. If its the only attachment for its linked message, the entire
   * message is deleted.
   */
  @WorkerThread
  public static void deleteAttachment(@NonNull Context context,
                                      @NonNull DatabaseAttachment attachment)
  {
    AttachmentId attachmentId    = attachment.getAttachmentId();
    long         mmsId           = attachment.getMmsId();
    int          attachmentCount = DatabaseComponent.get(context).attachmentDatabase()
        .getAttachmentsForMessage(mmsId)
        .size();

    if (attachmentCount <= 1) {
      DatabaseComponent.get(context).mmsDatabase().deleteMessage(mmsId);
    } else {
      DatabaseComponent.get(context).attachmentDatabase().deleteAttachment(attachmentId);
    }
  }

  @WorkerThread
  private static boolean isFromUnknownContact(@NonNull Context context, @NonNull DatabaseAttachment attachment) {
    // We don't allow attachments to be sent unless we're friends with someone or the attachment is sent
    // in a group context. Auto-downloading attachments is therefore fine.
    return false;
  }
}
