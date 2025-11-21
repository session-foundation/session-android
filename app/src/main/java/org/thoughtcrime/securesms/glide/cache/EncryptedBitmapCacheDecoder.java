package org.thoughtcrime.securesms.glide.cache;


import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.resource.bitmap.StreamBitmapDecoder;

import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.crypto.AttachmentSecretProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class EncryptedBitmapCacheDecoder extends EncryptedCoder implements ResourceDecoder<File, Bitmap> {

  private static final String TAG = EncryptedBitmapCacheDecoder.class.getSimpleName();

  private final StreamBitmapDecoder streamBitmapDecoder;
  private final AttachmentSecretProvider attachmentSecretProvider;

  public EncryptedBitmapCacheDecoder(@NonNull AttachmentSecretProvider attachmentSecretProvider, @NonNull StreamBitmapDecoder streamBitmapDecoder) {
    this.attachmentSecretProvider = attachmentSecretProvider;
    this.streamBitmapDecoder = streamBitmapDecoder;
  }

  @Override
  public boolean handles(@NonNull File source, @NonNull Options options)
      throws IOException
  {
    Log.i(TAG, "Checking item for encrypted Bitmap cache decoder: " + source.toString());

    try (InputStream inputStream = createEncryptedInputStream(attachmentSecretProvider.getOrCreateAttachmentSecret().getModernKey(), source)) {
      return streamBitmapDecoder.handles(inputStream, options);
    } catch (IOException e) {
      Log.w(TAG, e);
      return false;
    }
  }

  @Nullable
  @Override
  public Resource<Bitmap> decode(@NonNull File source, int width, int height, @NonNull Options options)
      throws IOException
  {
    Log.i(TAG, "Encrypted Bitmap cache decoder running: " + source.toString());
    try (InputStream inputStream = createEncryptedInputStream(attachmentSecretProvider.getOrCreateAttachmentSecret().getModernKey(), source)) {
      return streamBitmapDecoder.decode(inputStream, width, height, options);
    }
  }
}
