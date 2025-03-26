package org.thoughtcrime.securesms.mms;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.Nullable;
import org.session.libsession.messaging.sending_receiving.attachments.Attachment;
import org.session.libsession.utilities.MediaTypes;

public class GifSlide extends ImageSlide {

    public GifSlide(Context context, Attachment attachment) { super(context, attachment); }

    public GifSlide(Context context, Uri uri, String filename, long size, int width, int height, @Nullable String caption) {
        super(context, constructAttachmentFromUri(context, uri, MediaTypes.IMAGE_GIF, size, width, height, true, filename, caption, false, false));
    }

    @Override
    @Nullable
    public Uri getThumbnailUri() { return getUri(); }
}
