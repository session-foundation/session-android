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
import android.database.Cursor;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.session.libsession.utilities.WindowDebouncer;
import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;

import java.util.Arrays;
import java.util.Set;

import javax.inject.Provider;

public abstract class Database {

  protected static final String ID_WHERE = "_id = ?";
  protected static final String ID_IN = "_id IN (?)";

  private final Provider<SQLCipherOpenHelper> databaseHelper;
  protected final Context             context;
  private   final WindowDebouncer     conversationListNotificationDebouncer;
  private   final Runnable            conversationListUpdater;

  @SuppressLint("WrongConstant")
  public Database(Context context, Provider<SQLCipherOpenHelper> databaseHelper) {
    this.context = context;
    this.conversationListUpdater = () -> {
      context.getContentResolver().notifyChange(DatabaseContentProviders.ConversationList.CONTENT_URI, null);
    };
    this.databaseHelper = databaseHelper;
    this.conversationListNotificationDebouncer = ApplicationContext.getInstance(context).getConversationListDebouncer();
  }

  protected void notifyConversationListeners(Set<Long> threadIds) {
    for (long threadId : threadIds)
      notifyConversationListeners(threadId);
  }

  protected void notifyConversationListeners(long threadId) {
    ConversationNotificationDebouncer.Companion.get(context).notify(threadId);
  }

  protected void notifyConversationListListeners() {
    conversationListNotificationDebouncer.publish(conversationListUpdater);
  }

  protected void notifyStickerListeners() {
    context.getContentResolver().notifyChange(DatabaseContentProviders.Sticker.CONTENT_URI, null);
  }

  protected void notifyStickerPackListeners() {
    context.getContentResolver().notifyChange(DatabaseContentProviders.StickerPack.CONTENT_URI, null);
  }

  protected void notifyRecipientListeners() {
    context.getContentResolver().notifyChange(DatabaseContentProviders.Recipient.CONTENT_URI, null);
    notifyConversationListListeners();
  }

  protected void setNotifyConversationListeners(Cursor cursor, long threadId) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          cursor.setNotificationUris(
                  context.getContentResolver(),
                  Arrays.asList(
                          DatabaseContentProviders.Conversation.getUriForThread(threadId),
                          DatabaseContentProviders.Attachment.CONTENT_URI
                  )
          );
      } else {
        cursor.setNotificationUri(
                context.getContentResolver(),
                DatabaseContentProviders.Conversation.getUriForThread(threadId)
        );
      }
  }

  protected void setNotifyConversationListListeners(Cursor cursor) {
    cursor.setNotificationUri(context.getContentResolver(), DatabaseContentProviders.ConversationList.CONTENT_URI);
  }

  protected void registerAttachmentListeners(@NonNull ContentObserver observer) {
    context.getContentResolver().registerContentObserver(DatabaseContentProviders.Attachment.CONTENT_URI,
                                                         true,
                                                         observer);
  }

  protected void notifyAttachmentListeners() {
    context.getContentResolver().notifyChange(DatabaseContentProviders.Attachment.CONTENT_URI, null);
  }


  protected SQLiteDatabase getReadableDatabase() {
    return databaseHelper.get().getReadableDatabase();
  }

  protected SQLiteDatabase getWritableDatabase() {
    return databaseHelper.get().getWritableDatabase();
  }

}
