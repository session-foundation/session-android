package org.thoughtcrime.securesms.glide.cache;


import androidx.annotation.NonNull;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.crypto.AttachmentSecretProvider;

import com.bumptech.glide.load.EncodeStrategy;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceEncoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.util.ByteBufferUtil;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

public class EncryptedGifDrawableResourceEncoder extends EncryptedCoder implements ResourceEncoder<GifDrawable> {

  private static final String TAG = EncryptedGifDrawableResourceEncoder.class.getSimpleName();

  private final AttachmentSecretProvider secretProvider;

  public EncryptedGifDrawableResourceEncoder(@NonNull AttachmentSecretProvider secretProvider) {
    this.secretProvider = secretProvider;
  }

  @Override
  public EncodeStrategy getEncodeStrategy(@NonNull Options options) {
    return EncodeStrategy.TRANSFORMED;
  }

  @Override
  public boolean encode(@NonNull Resource<GifDrawable> data, @NonNull File file, @NonNull Options options) {
    GifDrawable drawable = data.get();

    try (OutputStream outputStream = createEncryptedOutputStream(secretProvider.getOrCreateAttachmentSecret().getModernKey(), file)) {
      ByteBufferUtil.toStream(drawable.getBuffer(), outputStream);
      return true;
    } catch (IOException e) {
      Log.w(TAG, e);
      return false;
    }
  }
}
