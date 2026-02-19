package org.thoughtcrime.securesms.database;

import android.content.Context;
import android.database.Cursor;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.session.libsession.utilities.Address;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;

import java.util.Collection;
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

  public abstract void deleteMessage(long messageId);
  public abstract void deleteMessages(Collection<Long> messageIds);

  public abstract void updateThreadId(long fromId, long toId);

  public abstract String getTypeColumn();


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
