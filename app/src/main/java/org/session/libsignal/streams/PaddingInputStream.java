package org.session.libsignal.streams;

import org.session.libsignal.utilities.Util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public class PaddingInputStream extends FilterInputStream {

  private long paddingRemaining;

  public PaddingInputStream(InputStream inputStream, long plaintextLength) {
    super(inputStream);
    this.paddingRemaining = getPaddedSize(plaintextLength) - plaintextLength;
  }

  @Override
  public int read() throws IOException {
    int result = super.read();
    if (result != -1) return result;

    if (paddingRemaining > 0) {
      paddingRemaining--;
      return 0x00;
    }

    return -1;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    int result = super.read(buffer, offset, length);
    if (result != -1) return result;

    if (paddingRemaining > 0) {
      length = Math.min(length, Util.toIntExact(paddingRemaining));
      paddingRemaining -= length;
      return length;
    }

    return -1;
  }

  @Override
  public int read(byte[] buffer) throws IOException {
    return read(buffer, 0, buffer.length);
  }

  @Override
  public int available() throws IOException {
    return super.available() + Util.toIntExact(paddingRemaining);
  }

  public static long getPaddedSize(long size) {
    return Math.max(
            541L,
            Math.min(
                    10_000_000L,
                    (long) Math.floor(Math.pow(
                            1.05,
                            Math.ceil(Math.log(Math.max(1, size)) / Math.log(1.05))
                    ))
            )
    );
  }
}
