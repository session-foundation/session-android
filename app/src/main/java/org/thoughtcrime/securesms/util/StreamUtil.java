package org.thoughtcrime.securesms.util;

import androidx.annotation.Nullable;

import org.session.libsignal.utilities.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Utility methods for input and output streams.
 */
public final class StreamUtil {

    private static final String TAG = Log.tag(StreamUtil.class);

    private StreamUtil() {}

    public static void close(@Nullable Closeable closeable) {
        if (closeable == null) return;

        try {
            closeable.close();
        } catch (IOException e) {
            Log.w(TAG, e);
        }
    }


    public static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        long total = 0;

        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            total += read;
        }

        in.close();
        out.close();

        return total;
    }
}
