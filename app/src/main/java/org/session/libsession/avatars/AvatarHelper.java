package org.session.libsession.avatars;

import android.content.Context;

import androidx.annotation.NonNull;

import org.session.libsession.utilities.Address;

import java.io.File;

/**
 * @deprecated We no longer use these address-based avatars. All avatars are now stored as sha256 of
 * urls encrypted locally. Look at {@link org.thoughtcrime.securesms.attachments.LocalEncryptedFileOutputStream},
 * {@link org.thoughtcrime.securesms.glide.RecipientAvatarDownloadManager} for more information.
 *
 * Once the migration grace period is over, this class shall be removed.
 */
@Deprecated(forRemoval = true)
public class AvatarHelper {

  private static final String AVATAR_DIRECTORY = "avatars";

  public static void delete(@NonNull Context context, @NonNull Address address) {
    getAvatarFile(context, address).delete();
  }

  public static @NonNull File getAvatarFile(@NonNull Context context, @NonNull Address address) {
    File avatarDirectory = new File(context.getFilesDir(), AVATAR_DIRECTORY);
    avatarDirectory.mkdirs();

    return new File(avatarDirectory, new File(address.toString()).getName());
  }

}
