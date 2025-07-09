package org.session.libsession.utilities;

import androidx.annotation.NonNull;

import org.session.libsignal.utilities.Base64;

import java.io.IOException;

public class ProfileKeyUtil {

  public static final int PROFILE_KEY_BYTES = 32;


  public static @NonNull byte[] getProfileKeyFromEncodedString(String encodedProfileKey) {
    try {
      return Base64.decode(encodedProfileKey);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  public static @NonNull String generateEncodedProfileKey() {
    return Util.getSecret(PROFILE_KEY_BYTES);
  }
}
