package org.thoughtcrime.securesms.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.Document;
import org.session.libsession.utilities.IdentityKeyMismatch;
import org.session.libsession.utilities.IdentityKeyMismatchList;
import org.session.libsignal.crypto.IdentityKey;
import org.session.libsignal.utilities.JsonUtil;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.database.model.MessageRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.inject.Provider;

public abstract class MessagingDatabase extends Database implements MmsSmsColumns {

  private static final String TAG = MessagingDatabase.class.getSimpleName();

  public MessagingDatabase(Context context, Provider<SQLCipherOpenHelper> databaseHelper) {
    super(context, databaseHelper);
  }

  protected abstract String getTableName();

  public abstract void markExpireStarted(long messageId, long startTime);

  public abstract void markAsSent(long messageId, boolean sent);

  public abstract void markAsSyncing(long id);

  public abstract void markAsResyncing(long id);

  public abstract void markAsSyncFailed(long id);


  public abstract void markAsDeleted(long messageId, boolean isOutgoing, String displayedMessage);

  public abstract List<Long> getExpiredMessageIDs(long nowMills);

  public abstract long getNextExpiringTimestamp();

  public abstract boolean deleteMessage(long messageId);
  public abstract boolean deleteMessages(long[] messageId, long threadId);

  public abstract void updateThreadId(long fromId, long toId);

  public abstract MessageRecord getMessageRecord(long messageId) throws NoSuchMessageException;

  public abstract String getTypeColumn();

  public void addMismatchedIdentity(long messageId, Address address, IdentityKey identityKey) {
    try {
      addToDocument(messageId, MISMATCHED_IDENTITIES,
                    new IdentityKeyMismatch(address, identityKey),
                    IdentityKeyMismatchList.class);
    } catch (IOException e) {
      Log.w(TAG, e);
    }
  }

  public void removeMismatchedIdentity(long messageId, Address address, IdentityKey identityKey) {
    try {
      removeFromDocument(messageId, MISMATCHED_IDENTITIES,
                         new IdentityKeyMismatch(address, identityKey),
                         IdentityKeyMismatchList.class);
    } catch (IOException e) {
      Log.w(TAG, e);
    }
  }

  protected <D extends Document<I>, I> void removeFromDocument(long messageId, String column, I object, Class<D> clazz) throws IOException {
    SQLiteDatabase database = getWritableDatabase();
    database.beginTransaction();

    try {
      D           document = getDocument(database, messageId, column, clazz);
      Iterator<I> iterator = document.getList().iterator();

      while (iterator.hasNext()) {
        I item = iterator.next();

        if (item.equals(object)) {
          iterator.remove();
          break;
        }
      }

      setDocument(database, messageId, column, document);
      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }
  }

  protected <T extends Document<I>, I> void addToDocument(long messageId, String column, final I object, Class<T> clazz) throws IOException {
    List<I> list = new ArrayList<I>() {{
      add(object);
    }};

    addToDocument(messageId, column, list, clazz);
  }

  protected <T extends Document<I>, I> void addToDocument(long messageId, String column, List<I> objects, Class<T> clazz) throws IOException {
    SQLiteDatabase database = getWritableDatabase();
    database.beginTransaction();

    try {
      T document = getDocument(database, messageId, column, clazz);
      document.getList().addAll(objects);
      setDocument(database, messageId, column, document);

      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }
  }

  private void setDocument(SQLiteDatabase database, long messageId, String column, Document document) throws IOException {
    ContentValues contentValues = new ContentValues();

    if (document == null || document.size() == 0) {
      contentValues.put(column, (String)null);
    } else {
      contentValues.put(column, JsonUtil.toJsonThrows(document));
    }

    database.update(getTableName(), contentValues, ID_WHERE, new String[] {String.valueOf(messageId)});
  }

  private <D extends Document> D getDocument(SQLiteDatabase database, long messageId,
                                             String column, Class<D> clazz)
  {
    Cursor cursor = null;

    try {
      cursor = database.query(getTableName(), new String[] {column},
                              ID_WHERE, new String[] {String.valueOf(messageId)},
                              null, null, null);

      if (cursor != null && cursor.moveToNext()) {
        String document = cursor.getString(cursor.getColumnIndexOrThrow(column));

        try {
          if (!TextUtils.isEmpty(document)) {
            return JsonUtil.fromJson(document, clazz);
          }
        } catch (IOException e) {
          Log.w(TAG, e);
        }
      }

      try {
        return clazz.newInstance();
      } catch (InstantiationException e) {
        throw new AssertionError(e);
      } catch (IllegalAccessException e) {
        throw new AssertionError(e);
      }

    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public void migrateThreadId(long oldThreadId, long newThreadId) {
    SQLiteDatabase db = getWritableDatabase();
    String where = THREAD_ID+" = ?";
    String[] args = new String[]{oldThreadId+""};
    ContentValues contentValues = new ContentValues();
    contentValues.put(THREAD_ID, newThreadId);
    db.update(getTableName(), contentValues, where, args);
  }

  public boolean isOutgoing(long messageId) {
    SQLiteDatabase db = getReadableDatabase();
    try(Cursor cursor = db.query(getTableName(), new String[]{getTypeColumn()},
            ID_WHERE, new String[]{String.valueOf(messageId)},
            null, null, null)) {
      if (cursor != null && cursor.moveToNext()) {
        return MmsSmsColumns.Types.isOutgoingMessageType(cursor.getLong(0));
      }
    }
    return false;
  }

  public static class SyncMessageId {

    private final Address address;
    private final long   timetamp;

    public SyncMessageId(Address address, long timetamp) {
      this.address  = address;
      this.timetamp = timetamp;
    }

    public Address getAddress() {
      return address;
    }

    public long getTimetamp() {
      return timetamp;
    }
  }

  public static class InsertResult {
    private final long messageId;
    private final long threadId;

    public InsertResult(long messageId, long threadId) {
      this.messageId = messageId;
      this.threadId = threadId;
    }

    public long getMessageId() {
      return messageId;
    }

    public long getThreadId() {
      return threadId;
    }
  }
}
