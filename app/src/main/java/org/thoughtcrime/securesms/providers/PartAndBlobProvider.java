/*
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.providers;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;

import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId;
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment;
import org.session.libsession.utilities.Util;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.dependencies.DatabaseComponent;
import org.thoughtcrime.securesms.mms.PartUriParser;
import org.thoughtcrime.securesms.service.KeyCachingService;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import network.loki.messenger.BuildConfig;

public class PartAndBlobProvider extends ContentProvider {

  private static final String TAG = PartAndBlobProvider.class.getSimpleName();

  private static final String CONTENT_URI_STRING = "content://network.loki.provider.securesms" + BuildConfig.AUTHORITY_POSTFIX + "/part";
  private static final Uri    CONTENT_URI        = Uri.parse(CONTENT_URI_STRING);
  private static final int    SINGLE_ROW         = 1;
  private static final int    BLOB_ROW           = 2; // New constant for blob URIs

  private static final UriMatcher uriMatcher;

  static {
    uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    uriMatcher.addURI("network.loki.provider.securesms" + BuildConfig.AUTHORITY_POSTFIX, "part/*/#", SINGLE_ROW);
    uriMatcher.addURI("network.loki.provider.securesms" + BuildConfig.AUTHORITY_POSTFIX, "blob/*/*/*/*/*", BLOB_ROW); // Add blob pattern
  }

  @Override
  public boolean onCreate() {
    Log.i(TAG, "onCreate()");
    return true;
  }

  public static Uri getContentUri(AttachmentId attachmentId) {
    Uri uri = Uri.withAppendedPath(CONTENT_URI, String.valueOf(attachmentId.getUniqueId()));
    return ContentUris.withAppendedId(uri, attachmentId.getRowId());
  }

  @Override
  public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
    Log.i(TAG, "openFile() called for URI: " + uri);

    if (KeyCachingService.isLocked(getContext())) {
      Log.w(TAG, "masterSecret was null, abandoning.");
      return null;
    }

    switch (uriMatcher.match(uri)) {
      case SINGLE_ROW:
        Log.i(TAG, "Parting out a single row...");
        try {
          final PartUriParser partUri = new PartUriParser(uri);
          return getParcelStreamForAttachment(partUri.getPartId());
        } catch (IOException ioe) {
          Log.w(TAG, ioe);
          throw new FileNotFoundException("Error opening file");
        }

      case BLOB_ROW:
        Log.i(TAG, "Handling blob URI...");
        try {
          return getParcelStreamForBlob(uri);
        } catch (IOException ioe) {
          Log.w(TAG, "Error opening blob file", ioe);
          throw new FileNotFoundException("Error opening blob file");
        }
    }

    throw new FileNotFoundException("Request for bad part.");
  }

  @Override
  public int delete(@NonNull Uri arg0, String arg1, String[] arg2) {
    Log.i(TAG, "delete() called");
    return 0;
  }

  @Override
  public String getType(@NonNull Uri uri) {
    Log.i(TAG, "getType() called: " + uri);

    switch (uriMatcher.match(uri)) {
      case SINGLE_ROW:
        PartUriParser      partUriParser = new PartUriParser(uri);
        DatabaseAttachment attachment    = DatabaseComponent.get(getContext()).attachmentDatabase()
                .getAttachment(partUriParser.getPartId());

        if (attachment != null) {
          return attachment.getContentType();
        }
        break;

      case BLOB_ROW:
        // For blob URIs, get the mime type from the BlobProvider
        return BlobUtils.getMimeType(uri);
    }

    return null;
  }

  @Override
  public Uri insert(@NonNull Uri arg0, ContentValues arg1) {
    Log.i(TAG, "insert() called");
    return null;
  }

  @Override
  public Cursor query(@NonNull Uri url, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    Log.i(TAG, "query() called: " + url);

    if (projection == null || projection.length <= 0) return null;

    switch (uriMatcher.match(url)) {
      case SINGLE_ROW:
        PartUriParser      partUri      = new PartUriParser(url);
        DatabaseAttachment attachment   = DatabaseComponent.get(getContext()).attachmentDatabase().getAttachment(partUri.getPartId());

        if (attachment == null) return null;

        MatrixCursor       matrixCursor = new MatrixCursor(projection, 1);
        Object[]           resultRow    = new Object[projection.length];

        for (int i = 0; i < projection.length; i++) {
          if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) {
            resultRow[i] = attachment.getFilename();
          }
        }

        matrixCursor.addRow(resultRow);
        return matrixCursor;

      case BLOB_ROW:
        // For blob URIs, create a cursor with blob information
        MatrixCursor blobCursor = new MatrixCursor(projection, 1);
        Object[] blobRow = new Object[projection.length];

        for (int i = 0; i < projection.length; i++) {
          if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) {
            blobRow[i] = BlobUtils.getFileName(url);
          } else if (OpenableColumns.SIZE.equals(projection[i])) {
            blobRow[i] = BlobUtils.getFileSize(url);
          }
        }

        blobCursor.addRow(blobRow);
        return blobCursor;
    }

    return null;
  }

  @Override
  public int update(@NonNull Uri arg0, ContentValues arg1, String arg2, String[] arg3) {
    Log.i(TAG, "update() called");
    return 0;
  }

  private ParcelFileDescriptor getParcelStreamForAttachment(AttachmentId attachmentId) throws IOException {
    return getStreamingParcelFileDescriptor(() ->
            DatabaseComponent.get(getContext()).attachmentDatabase().getAttachmentStream(attachmentId, 0)
    );
  }

  private ParcelFileDescriptor getStreamingParcelFileDescriptor(InputStreamProvider provider) throws IOException {
    try {
      ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
      ParcelFileDescriptor readSide = pipe[0];
      ParcelFileDescriptor writeSide = pipe[1];

      new Thread(() -> {
        try (InputStream inputStream = provider.getInputStream();
             OutputStream outputStream = new ParcelFileDescriptor.AutoCloseOutputStream(writeSide)) {

          Util.copy(inputStream, outputStream);

        } catch (IOException e) {
          Log.w(TAG, "Error streaming data", e);
          try {
            writeSide.closeWithError("Error streaming data: " + e.getMessage());
          } catch (IOException closeException) {
            Log.w(TAG, "Error closing write side of pipe", closeException);
          }
        }
      }).start();

      return readSide;

    } catch (IOException e) {
      Log.w(TAG, "Error creating streaming pipe", e);
      throw new FileNotFoundException("Error creating streaming pipe: " + e.getMessage());
    }
  }

  private interface InputStreamProvider {
    InputStream getInputStream() throws IOException;
  }

  private ParcelFileDescriptor getParcelStreamForBlob(Uri uri) throws IOException {
    // Always use streaming for blobs since they're often shared media files that can be large
    return getStreamingParcelFileDescriptor(() ->
            BlobUtils.getInstance().getStream(getContext(), uri)
    );
  }
}