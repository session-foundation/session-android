package org.session.libsession.avatars;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;

import org.session.libsession.utilities.Address;
import org.session.libsignal.utilities.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;

/**
 * @deprecated We no longer use these address-based avatars. All avatars are now stored as sha256 of
 * urls encrypted locally. Look at {@link org.thoughtcrime.securesms.attachments.LocalEncryptedFileOutputStream},
 * {@link org.thoughtcrime.securesms.attachments.RemoteFileDownloadWorker},
 * {@link org.thoughtcrime.securesms.glide.RecipientAvatarDownloadManager} for more information.
 *
 * Once the migration grace period is over, this class shall be removed.
 */
@Deprecated(forRemoval = true)
public class AvatarHelper {

  private static final String AVATAR_DIRECTORY = "avatars";

  public static InputStream getInputStreamFor(@NonNull Context context, @NonNull Address address)
          throws FileNotFoundException
  {
      return new FileInputStream(getAvatarFile(context, address));
  }

  public static List<File> getAvatarFiles(@NonNull Context context) {
    File   avatarDirectory = new File(context.getFilesDir(), AVATAR_DIRECTORY);
    File[] results         = avatarDirectory.listFiles();

    if (results == null) return new LinkedList<>();
    else                 return Stream.of(results).toList();
  }

  public static void delete(@NonNull Context context, @NonNull Address address) {
    getAvatarFile(context, address).delete();
  }

  public static @NonNull File getAvatarFile(@NonNull Context context, @NonNull Address address) {
    File avatarDirectory = new File(context.getFilesDir(), AVATAR_DIRECTORY);
    avatarDirectory.mkdirs();

    return new File(avatarDirectory, new File(address.toString()).getName());
  }

  public static boolean avatarFileExists(@NonNull Context context , @NonNull Address address) {
    File avatarFile = getAvatarFile(context, address);
    return avatarFile.exists();
  }

  public static void setAvatar(@NonNull Context context, @NonNull Address address, @Nullable byte[] data)
    throws IOException
  {
    if (data == null)  {
      delete(context, address);
    } else {
      try (FileOutputStream out = new FileOutputStream(getAvatarFile(context, address))) {
        out.write(data);
      }
    }
  }

}
