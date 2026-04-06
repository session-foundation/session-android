package org.thoughtcrime.securesms.mms;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.data.DataFetcher;

import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.util.MediaUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

class DecryptableStreamLocalUriFetcher implements DataFetcher<ByteBuffer> {

  private static final String TAG = DecryptableStreamLocalUriFetcher.class.getSimpleName();

  private final Context context;
  private final Uri uri;

  DecryptableStreamLocalUriFetcher(Context context, Uri uri) {
    this.context = context;
    this.uri = uri;
  }

  @Override
  public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super ByteBuffer> callback) {
    try {
      callback.onDataReady(ByteBuffer.wrap(readAllBytes()));
    } catch (IOException e) {
      Log.w(TAG, e);
      callback.onLoadFailed(e);
    }
  }

  private byte[] readAllBytes() throws IOException {
    if (MediaUtil.hasVideoThumbnail(uri)) {
      Bitmap thumbnail = MediaUtil.getVideoThumbnail(context, uri);
      if (thumbnail != null) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        thumbnail.compress(Bitmap.CompressFormat.JPEG, 100, baos);
        return baos.toByteArray();
      }
    }

    try (InputStream stream = PartAuthority.getAttachmentStream(context, uri)) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int bytesRead;
      while ((bytesRead = stream.read(chunk)) != -1) {
        buffer.write(chunk, 0, bytesRead);
      }
      return buffer.toByteArray();
    }
  }

  @Override
  public void cleanup() {}

  @Override
  public void cancel() {}

  @NonNull
  @Override
  public Class<ByteBuffer> getDataClass() {
    return ByteBuffer.class;
  }

  @NonNull
  @Override
  public DataSource getDataSource() {
    return DataSource.LOCAL;
  }
}
