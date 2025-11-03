/**
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
package org.thoughtcrime.securesms.database;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.ContentObserver;

import androidx.annotation.NonNull;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;

import javax.inject.Provider;

public abstract class Database {

  protected static final String ID_WHERE = "_id = ?";
  protected static final String ID_IN = "_id IN (?)";

  private final Provider<SQLCipherOpenHelper> databaseHelper;
  protected final Context             context;

  @SuppressLint("WrongConstant")
  public Database(Context context, Provider<SQLCipherOpenHelper> databaseHelper) {
    this.context = context;
    this.databaseHelper = databaseHelper;
  }


  protected void notifyStickerListeners() {
    context.getContentResolver().notifyChange(DatabaseContentProviders.Sticker.CONTENT_URI, null);
  }

  protected void notifyStickerPackListeners() {
    context.getContentResolver().notifyChange(DatabaseContentProviders.StickerPack.CONTENT_URI, null);
  }

  protected void registerAttachmentListeners(@NonNull ContentObserver observer) {
    context.getContentResolver().registerContentObserver(DatabaseContentProviders.Attachment.CONTENT_URI,
                                                         true,
                                                         observer);
  }

  protected SQLiteDatabase getReadableDatabase() {
    return databaseHelper.get().getReadableDatabase();
  }

  protected SQLiteDatabase getWritableDatabase() {
    return databaseHelper.get().getWritableDatabase();
  }

}
