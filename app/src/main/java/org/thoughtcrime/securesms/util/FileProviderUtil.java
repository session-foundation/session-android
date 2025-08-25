package org.thoughtcrime.securesms.util;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import java.io.File;

import network.loki.messenger.BuildConfig;

public class FileProviderUtil {

  public static final String AUTHORITY = "network.loki.securesms.fileprovider" + BuildConfig.AUTHORITY_POSTFIX;

  @NonNull
  public static Uri getUriFor(@NonNull Context context, @NonNull File file) {
    return FileProvider.getUriForFile(context, AUTHORITY, file);
  }

  public static boolean delete(@NonNull Context context, @NonNull Uri uri) {
    if (AUTHORITY.equals(uri.getAuthority())) {
      return context.getContentResolver().delete(uri, null, null) > 0;
    }
    return new File(uri.getPath()).delete();
  }
}
