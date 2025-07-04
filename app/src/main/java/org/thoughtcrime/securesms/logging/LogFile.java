package org.thoughtcrime.securesms.logging;

import static org.session.libsignal.crypto.CipherUtil.CIPHER_LOCK;
import static org.session.libsignal.utilities.Util.SECURE_RANDOM;

import androidx.annotation.NonNull;

import org.session.libsession.utilities.Conversions;
import org.session.libsession.utilities.Util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

class LogFile {

  public static class GrowingBuffer {

    private byte[] buffer;

    public byte[] get(int minLength) {
      if (buffer == null || buffer.length < minLength) {
        buffer = new byte[minLength];
      }
      return buffer;
    }
  }

  public static class Writer {

    private final byte[]        ivBuffer         = new byte[16];
    private final GrowingBuffer ciphertextBuffer = new GrowingBuffer();

    private final byte[]               secret;
    final File                 file;
    private final Cipher               cipher;
    private final BufferedOutputStream outputStream;

    Writer(@NonNull byte[] secret, @NonNull File file) throws IOException {
      this.secret       = secret;
      this.file         = file;
      this.outputStream = new BufferedOutputStream(new FileOutputStream(file, true));

      try {
        this.cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
        throw new AssertionError(e);
      }
    }

    void writeEntry(@NonNull String entry, boolean flush) throws IOException {
      SECURE_RANDOM.nextBytes(ivBuffer);

      byte[] plaintext = entry.getBytes();
      try {
        synchronized (CIPHER_LOCK) {
          cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(ivBuffer));

          int cipherLength = cipher.getOutputSize(plaintext.length);
          byte[] ciphertext = ciphertextBuffer.get(cipherLength);
          cipherLength = cipher.doFinal(plaintext, 0, plaintext.length, ciphertext);

          outputStream.write(ivBuffer);
          outputStream.write(Conversions.intToByteArray(cipherLength));
          outputStream.write(ciphertext, 0, cipherLength);
        }

        if (flush) {
          outputStream.flush();
        }
      } catch (ShortBufferException | InvalidAlgorithmParameterException | InvalidKeyException | BadPaddingException | IllegalBlockSizeException e) {
        throw new AssertionError(e);
      }
    }

    void flush() throws IOException {
      outputStream.flush();
    }

    long getLogSize() {
      return file.length();
    }

    void close() {
      Util.close(outputStream);
    }
  }

  static class Reader implements Closeable {

    private final byte[]        ivBuffer         = new byte[16];
    private final byte[]        intBuffer        = new byte[4];
    private final GrowingBuffer ciphertextBuffer = new GrowingBuffer();

    private final byte[]              secret;
    private final Cipher              cipher;
    private final BufferedInputStream inputStream;

    Reader(@NonNull byte[] secret, @NonNull File file) throws IOException {
      this.secret      = secret;
      this.inputStream = new BufferedInputStream(new FileInputStream(file));

      try {
        this.cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
        throw new AssertionError(e);
      }
    }

    String readAll() throws IOException {
      StringBuilder builder = new StringBuilder();

      String entry;
      while ((entry = readEntry()) != null) {
        builder.append(entry).append('\n');
      }

      return builder.toString();
    }

    @Override
    public void close() throws IOException {
      Util.close(inputStream);
    }

    String readEntry() throws IOException {
      byte[] plaintext = readEntryBytes();
      if (plaintext == null) {
        return null;
      }
      return new String(plaintext);
    }

    byte[] readEntryBytes() throws IOException {
      try {
        // Read the IV and length
        Util.readFully(inputStream, ivBuffer);
        Util.readFully(inputStream, intBuffer);
      } catch (EOFException e) {
        // End of file reached before a full header could be read.
        return null;
      }

      int length = Conversions.byteArrayToInt(intBuffer);
      byte[] ciphertext = ciphertextBuffer.get(length);

      try {
        Util.readFully(inputStream, ciphertext, length);
      } catch (EOFException e) {
        // Incomplete ciphertext – likely due to a partially written record.
        return null;
      }

      try {
        synchronized (CIPHER_LOCK) {
          cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(ivBuffer));
          byte[] plaintext = cipher.doFinal(ciphertext, 0, length);
          return plaintext;
        }
      } catch (BadPaddingException e) {
        // Bad padding likely indicates a corrupted or incomplete entry.
        // Instead of throwing an error, treat this as the end of the log.
        return null;
      } catch (InvalidKeyException | InvalidAlgorithmParameterException
               | IllegalBlockSizeException e) {
        throw new AssertionError(e);
      }
    }
  }
}
