package org.thoughtcrime.securesms.database;

import static org.session.libsession.utilities.GroupUtil.CLOSED_GROUP_PREFIX;
import static org.session.libsession.utilities.GroupUtil.COMMUNITY_PREFIX;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_ID;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MergeCursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.jetbrains.annotations.NotNull;
import org.session.libsession.snode.SnodeAPI;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.Contact;
import org.session.libsession.utilities.DelimiterUtil;
import org.session.libsession.utilities.DistributionTypes;
import org.session.libsession.utilities.GroupRecord;
import org.session.libsession.utilities.TextSecurePreferences;
import org.session.libsession.utilities.Util;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsession.utilities.recipients.Recipient.RecipientSettings;
import org.session.libsignal.utilities.IdPrefix;
import org.session.libsignal.utilities.Log;
import org.session.libsignal.utilities.Pair;
import org.session.libsignal.utilities.guava.Optional;
import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.contacts.ContactUtil;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.database.model.ThreadRecord;
import org.thoughtcrime.securesms.dependencies.DatabaseComponent;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.mms.SlideDeck;
import org.thoughtcrime.securesms.notifications.MarkReadReceiver;
import org.thoughtcrime.securesms.util.SessionMetaProtocol;

import java.io.Closeable;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ThreadDatabase extends Database {

  public interface ConversationThreadUpdateListener {
    void threadCreated(@NonNull Address address, long threadId);
    void threadDeleted(@NonNull Address address, long threadId);
  }

  private static final String TAG = ThreadDatabase.class.getSimpleName();

  private final Map<Long, Address> addressCache = new HashMap<>();

  public  static final String TABLE_NAME             = "thread";
  public  static final String ID                     = "_id";
  public  static final String THREAD_CREATION_DATE   = "date";
  public  static final String MESSAGE_COUNT          = "message_count";
  public  static final String ADDRESS                = "recipient_ids";
  public  static final String SNIPPET                = "snippet";
  private static final String SNIPPET_CHARSET        = "snippet_cs";
  public  static final String READ                   = "read";
  public  static final String UNREAD_COUNT           = "unread_count";
  public  static final String UNREAD_MENTION_COUNT   = "unread_mention_count";
  public  static final String DISTRIBUTION_TYPE      = "type"; // See: DistributionTypes.kt
  private static final String ERROR                  = "error";
  public  static final String SNIPPET_TYPE           = "snippet_type";
  public  static final String SNIPPET_URI            = "snippet_uri";
  public  static final String ARCHIVED               = "archived";
  public  static final String STATUS                 = "status";
  public  static final String DELIVERY_RECEIPT_COUNT = "delivery_receipt_count";
  public  static final String READ_RECEIPT_COUNT     = "read_receipt_count";
  public  static final String EXPIRES_IN             = "expires_in";
  public  static final String LAST_SEEN              = "last_seen";
  public  static final String HAS_SENT               = "has_sent";
  public  static final String IS_PINNED              = "is_pinned";

  // Se asume que ya tienes (o tendrás por migración) la columna 'thread_key_alias'
  public static final String THREAD_KEY_ALIAS        = "thread_key_alias";

  private static final String ID_WHERE = ID + " = ?";

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " ("                    +
          ID + " INTEGER PRIMARY KEY, " + THREAD_CREATION_DATE + " INTEGER DEFAULT 0, "                  +
          MESSAGE_COUNT + " INTEGER DEFAULT 0, " + ADDRESS + " TEXT, " + SNIPPET + " TEXT, "             +
          SNIPPET_CHARSET + " INTEGER DEFAULT 0, " + READ + " INTEGER DEFAULT 1, "                       +
          DISTRIBUTION_TYPE + " INTEGER DEFAULT 0, " + ERROR + " INTEGER DEFAULT 0, "                    +
          SNIPPET_TYPE + " INTEGER DEFAULT 0, " + SNIPPET_URI + " TEXT DEFAULT NULL, "                   +
          ARCHIVED + " INTEGER DEFAULT 0, " + STATUS + " INTEGER DEFAULT 0, "                            +
          DELIVERY_RECEIPT_COUNT + " INTEGER DEFAULT 0, " + EXPIRES_IN + " INTEGER DEFAULT 0, "          +
          LAST_SEEN + " INTEGER DEFAULT 0, " + HAS_SENT + " INTEGER DEFAULT 0, "                         +
          READ_RECEIPT_COUNT + " INTEGER DEFAULT 0, " + UNREAD_COUNT + " INTEGER DEFAULT 0);";

  public static final String[] CREATE_INDEXES = {
          "CREATE INDEX IF NOT EXISTS thread_recipient_ids_index ON " + TABLE_NAME + " (" + ADDRESS + ");",
          "CREATE INDEX IF NOT EXISTS archived_count_index ON " + TABLE_NAME + " (" + ARCHIVED + ", " + MESSAGE_COUNT + ");",
  };

  private static final String[] THREAD_PROJECTION = {
          ID, THREAD_CREATION_DATE, MESSAGE_COUNT, ADDRESS, SNIPPET, SNIPPET_CHARSET, READ, UNREAD_COUNT, UNREAD_MENTION_COUNT, DISTRIBUTION_TYPE, ERROR, SNIPPET_TYPE,
          SNIPPET_URI, ARCHIVED, STATUS, DELIVERY_RECEIPT_COUNT, EXPIRES_IN, LAST_SEEN, READ_RECEIPT_COUNT, IS_PINNED
  };

  private static final List<String> TYPED_THREAD_PROJECTION = Stream.of(THREAD_PROJECTION)
          .map(columnName -> TABLE_NAME + "." + columnName)
          .toList();

  private static final List<String> COMBINED_THREAD_RECIPIENT_GROUP_PROJECTION = Stream.concat(Stream.concat(Stream.of(TYPED_THREAD_PROJECTION),
                          Stream.of(RecipientDatabase.TYPED_RECIPIENT_PROJECTION)),
                  Stream.of(GroupDatabase.TYPED_GROUP_PROJECTION))
          .toList();

  public static String getCreatePinnedCommand() {
    return "ALTER TABLE "+ TABLE_NAME + " " +
            "ADD COLUMN " + IS_PINNED + " INTEGER DEFAULT 0;";
  }

  public static String getUnreadMentionCountCommand() {
    return "ALTER TABLE "+ TABLE_NAME + " " +
            "ADD COLUMN " + UNREAD_MENTION_COUNT + " INTEGER DEFAULT 0;";
  }

  // Si tuvieras que migrar la columna 'thread_key_alias':
  // public static String getThreadKeyAliasCommand() {
  //   return "ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + THREAD_KEY_ALIAS + " TEXT DEFAULT NULL;";
  // }

  private ConversationThreadUpdateListener updateListener;

  public ThreadDatabase(Context context, SQLCipherOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  public void setUpdateListener(ConversationThreadUpdateListener updateListener) {
    this.updateListener = updateListener;
  }

  private long createThreadForRecipient(Address address, boolean group, int distributionType) {
    ContentValues contentValues = new ContentValues(4);
    long date                   = SnodeAPI.getNowWithOffset();

    contentValues.put(THREAD_CREATION_DATE, date - date % 1000);
    contentValues.put(ADDRESS, address.serialize());

    if (group) contentValues.put(DISTRIBUTION_TYPE, distributionType);

    contentValues.put(MESSAGE_COUNT, 0);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    return db.insert(TABLE_NAME, null, contentValues);
  }

  private void updateThread(long threadId, long count, String body, @Nullable Uri attachment,
                            long date, int status, int deliveryReceiptCount, long type, boolean unarchive,
                            long expiresIn, int readReceiptCount)
  {
    ContentValues contentValues = new ContentValues(7);
    contentValues.put(THREAD_CREATION_DATE, date - date % 1000);
    contentValues.put(MESSAGE_COUNT, count);
    if (!body.isEmpty()) {
      contentValues.put(SNIPPET, body);
    }
    contentValues.put(SNIPPET_URI, attachment == null ? null : attachment.toString());
    contentValues.put(SNIPPET_TYPE, type);
    contentValues.put(STATUS, status);
    contentValues.put(DELIVERY_RECEIPT_COUNT, deliveryReceiptCount);
    contentValues.put(READ_RECEIPT_COUNT, readReceiptCount);
    contentValues.put(EXPIRES_IN, expiresIn);

    if (unarchive) { contentValues.put(ARCHIVED, 0); }

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  public void clearSnippet(long threadId){
    ContentValues contentValues = new ContentValues(1);

    contentValues.put(SNIPPET, "");

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  public void updateSnippet(long threadId, String snippet, @Nullable Uri attachment, long date, long type, boolean unarchive) {
    ContentValues contentValues = new ContentValues(4);

    contentValues.put(THREAD_CREATION_DATE, date - date % 1000);
    if (!snippet.isEmpty()) {
      contentValues.put(SNIPPET, snippet);
    }
    contentValues.put(SNIPPET_TYPE, type);
    contentValues.put(SNIPPET_URI, attachment == null ? null : attachment.toString());

    if (unarchive) {
      contentValues.put(ARCHIVED, 0);
    }

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  private void deleteThread(long threadId) {
    Recipient recipient = getRecipientForThreadId(threadId);
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    int numberRemoved = db.delete(TABLE_NAME, ID_WHERE, new String[] {threadId + ""});
    addressCache.remove(threadId);
    notifyConversationListListeners();
    if (updateListener != null && numberRemoved > 0 && recipient != null) {
      updateListener.threadDeleted(recipient.getAddress(), threadId);
    }
  }

  private void deleteThreads(Set<Long> threadIds) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    String where      = "";

    for (long threadId : threadIds) { where += ID + " = '" + threadId + "' OR "; }

    where = where.substring(0, where.length() - 4);

    db.delete(TABLE_NAME, where, null);
    for (long threadId: threadIds) {
      addressCache.remove(threadId);
    }
    notifyConversationListListeners();
  }

  private void deleteAllThreads() {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.delete(TABLE_NAME, null, null);
    addressCache.clear();
    notifyConversationListListeners();
  }

  public void trimAllThreads(int length, ProgressListener listener) {
    Cursor cursor   = null;
    int threadCount = 0;
    int complete    = 0;

    try {
      cursor = this.getConversationList();

      if (cursor != null)
        threadCount = cursor.getCount();

      while (cursor != null && cursor.moveToNext()) {
        long threadId = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
        trimThread(threadId, length);

        listener.onProgress(++complete, threadCount);
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public void trimThread(long threadId, int length) {
    Log.i("ThreadDatabase", "Trimming thread: " + threadId + " to: " + length);
    Cursor cursor = null;

    try {
      cursor = DatabaseComponent.get(context).mmsSmsDatabase().getConversation(threadId, true);

      if (cursor != null && length > 0 && cursor.getCount() > length) {
        Log.w("ThreadDatabase", "Cursor count is greater than length!");
        cursor.moveToPosition(length - 1);

        long lastTweetDate = cursor.getLong(cursor.getColumnIndexOrThrow(MmsSmsColumns.NORMALIZED_DATE_RECEIVED));

        Log.i("ThreadDatabase", "Cut off tweet date: " + lastTweetDate);

        DatabaseComponent.get(context).smsDatabase().deleteMessagesInThreadBeforeDate(threadId, lastTweetDate);
        DatabaseComponent.get(context).mmsDatabase().deleteMessagesInThreadBeforeDate(threadId, lastTweetDate);

        update(threadId, false);
        notifyConversationListeners(threadId);
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public void trimThreadBefore(long threadId, long timestamp) {
    Log.i("ThreadDatabase", "Trimming thread: " + threadId + " before :"+timestamp);
    DatabaseComponent.get(context).smsDatabase().deleteMessagesInThreadBeforeDate(threadId, timestamp);
    DatabaseComponent.get(context).mmsDatabase().deleteMessagesInThreadBeforeDate(threadId, timestamp);
    update(threadId, false);
    notifyConversationListeners(threadId);
  }

  public List<MarkedMessageInfo> setRead(long threadId, long lastReadTime) {

    final List<MarkedMessageInfo> smsRecords = DatabaseComponent.get(context).smsDatabase().setMessagesRead(threadId, lastReadTime);
    final List<MarkedMessageInfo> mmsRecords = DatabaseComponent.get(context).mmsDatabase().setMessagesRead(threadId, lastReadTime);

    if (smsRecords.isEmpty() && mmsRecords.isEmpty()) {
      return Collections.emptyList();
    }

    ContentValues contentValues = new ContentValues(2);
    contentValues.put(READ, smsRecords.isEmpty() && mmsRecords.isEmpty());
    contentValues.put(LAST_SEEN, lastReadTime);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[] {threadId+""});

    notifyConversationListListeners();

    return new LinkedList<MarkedMessageInfo>() {{
      addAll(smsRecords);
      addAll(mmsRecords);
    }};
  }

  public List<MarkedMessageInfo> setRead(long threadId, boolean lastSeen) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(READ, 1);
    contentValues.put(UNREAD_COUNT, 0);
    contentValues.put(UNREAD_MENTION_COUNT, 0);

    if (lastSeen) {
      contentValues.put(LAST_SEEN, SnodeAPI.getNowWithOffset());
    }

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId+""});

    final List<MarkedMessageInfo> smsRecords = DatabaseComponent.get(context).smsDatabase().setMessagesRead(threadId);
    final List<MarkedMessageInfo> mmsRecords = DatabaseComponent.get(context).mmsDatabase().setMessagesRead(threadId);

    notifyConversationListListeners();

    return new LinkedList<MarkedMessageInfo>() {{
      addAll(smsRecords);
      addAll(mmsRecords);
    }};
  }

  public void setDistributionType(long threadId, int distributionType) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(DISTRIBUTION_TYPE, distributionType);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  public void setDate(long threadId, long date) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(THREAD_CREATION_DATE, date);
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    int updated = db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId+""});
    if (updated > 0) notifyConversationListListeners();
  }

  public int getDistributionType(long threadId) {
    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
    Cursor         cursor = db.query(TABLE_NAME, new String[]{DISTRIBUTION_TYPE}, ID_WHERE, new String[]{String.valueOf(threadId)}, null, null, null);

    try {
      if (cursor != null && cursor.moveToNext()) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(DISTRIBUTION_TYPE));
      }

      return DistributionTypes.DEFAULT;
    } finally {
      if (cursor != null) cursor.close();
    }

  }

  public Cursor searchConversationAddresses(String addressQuery) {
    if (addressQuery == null || addressQuery.isEmpty()) {
      return null;
    }

    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    String   selection      = TABLE_NAME + "." + ADDRESS + " LIKE ? AND " + TABLE_NAME + "." + MESSAGE_COUNT + " != 0";
    String[] selectionArgs  = new String[]{addressQuery+"%"};
    String query = createQuery(selection, 0);
    Cursor cursor = db.rawQuery(query, selectionArgs);
    return cursor;
  }

  public Cursor getFilteredConversationList(@Nullable List<Address> filter) {
    if (filter == null || filter.size() == 0)
      return null;

    SQLiteDatabase      db                   = databaseHelper.getReadableDatabase();
    List<List<Address>> partitionedAddresses = Util.partition(filter, 900);
    List<Cursor>        cursors              = new LinkedList<>();

    for (List<Address> addresses : partitionedAddresses) {
      String   selection      = TABLE_NAME + "." + ADDRESS + " = ?";
      String[] selectionArgs  = new String[addresses.size()];

      for (int i=0;i<addresses.size()-1;i++)
        selection += (" OR " + TABLE_NAME + "." + ADDRESS + " = ?");

      int i= 0;
      for (Address address : addresses) {
        selectionArgs[i++] = DelimiterUtil.escape(address.serialize(), ' ');
      }

      String query = createQuery(selection, 0);
      cursors.add(db.rawQuery(query, selectionArgs));
    }

    Cursor cursor = cursors.size() > 1 ? new MergeCursor(cursors.toArray(new Cursor[cursors.size()])) : cursors.get(0);
    setNotifyConversationListListeners(cursor);
    return cursor;
  }

  public Cursor getRecentConversationList(int limit) {
    SQLiteDatabase db    = databaseHelper.getReadableDatabase();
    String         query = createQuery(MESSAGE_COUNT + " != 0", limit);

    return db.rawQuery(query, null);
  }

  public int getUnapprovedConversationCount() {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor     = null;

    try {
      String query    = "SELECT COUNT (*) FROM " + TABLE_NAME +
              " LEFT OUTER JOIN " + RecipientDatabase.TABLE_NAME +
              " ON " + TABLE_NAME + "." + ADDRESS + " = " + RecipientDatabase.TABLE_NAME + "." + RecipientDatabase.ADDRESS +
              " LEFT OUTER JOIN " + GroupDatabase.TABLE_NAME +
              " ON " + TABLE_NAME + "." + ADDRESS + " = " + GroupDatabase.TABLE_NAME + "." + GROUP_ID +
              " WHERE " + MESSAGE_COUNT + " != 0 AND " + ARCHIVED + " = 0 AND " + HAS_SENT + " = 0 AND " +
              RecipientDatabase.TABLE_NAME + "." + RecipientDatabase.APPROVED + " = 0 AND " +
              RecipientDatabase.TABLE_NAME + "." + RecipientDatabase.BLOCK + " = 0 AND " +
              GroupDatabase.TABLE_NAME + "." + GROUP_ID + " IS NULL";
      cursor          = db.rawQuery(query, null);

      if (cursor != null && cursor.moveToFirst())
        return cursor.getInt(0);
    } finally {
      if (cursor != null)
        cursor.close();
    }

    return 0;
  }

  public long getLatestUnapprovedConversationTimestamp() {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor     = null;

    try {
      String where    = "SELECT " + THREAD_CREATION_DATE + " FROM " + TABLE_NAME +
              " LEFT OUTER JOIN " + RecipientDatabase.TABLE_NAME +
              " ON " + TABLE_NAME + "." + ADDRESS + " = " + RecipientDatabase.TABLE_NAME + "." + RecipientDatabase.ADDRESS +
              " LEFT OUTER JOIN " + GroupDatabase.TABLE_NAME +
              " ON " + TABLE_NAME + "." + ADDRESS + " = " + GroupDatabase.TABLE_NAME + "." + GROUP_ID +
              " WHERE " + MESSAGE_COUNT + " != 0 AND " + ARCHIVED + " = 0 AND " + HAS_SENT + " = 0 AND " +
              RecipientDatabase.TABLE_NAME + "." + RecipientDatabase.BLOCK + " = 0 AND " +
              RecipientDatabase.TABLE_NAME + "." + RecipientDatabase.APPROVED + " = 0 AND " +
              GroupDatabase.TABLE_NAME + "." + GROUP_ID + " IS NULL ORDER BY " + THREAD_CREATION_DATE + " DESC LIMIT 1";
      cursor          = db.rawQuery(where, null);

      if (cursor != null && cursor.moveToFirst())
        return cursor.getLong(0);
    } finally {
      if (cursor != null)
        cursor.close();
    }

    return 0;
  }

  public Cursor getConversationList() {
    String where  = "(" + MESSAGE_COUNT + " != 0 OR " + GroupDatabase.TABLE_NAME + "." + GROUP_ID + " LIKE '" + COMMUNITY_PREFIX + "%') " +
            "AND " + ARCHIVED + " = 0 ";
    return getConversationList(where);
  }

  public Cursor getBlindedConversationList() {
    String where  = TABLE_NAME + "." + ADDRESS + " LIKE '" + IdPrefix.BLINDED.getValue() + "%' ";
    return getConversationList(where);
  }

  public Cursor getApprovedConversationList() {
    String where  = "((" + HAS_SENT + " = 1 OR " + RecipientDatabase.APPROVED + " = 1 OR "+ GroupDatabase.TABLE_NAME +"."+GROUP_ID+" LIKE '"+CLOSED_GROUP_PREFIX+"%') OR " + GroupDatabase.TABLE_NAME + "." + GROUP_ID + " LIKE '" + COMMUNITY_PREFIX + "%') " +
            "AND " + ARCHIVED + " = 0 ";
    return getConversationList(where);
  }

  public Cursor getUnapprovedConversationList() {
    String where  = MESSAGE_COUNT + " != 0 AND " + ARCHIVED + " = 0 AND " + HAS_SENT + " = 0 AND " +
            RecipientDatabase.TABLE_NAME + "." + RecipientDatabase.APPROVED + " = 0 AND " +
            RecipientDatabase.TABLE_NAME + "." + RecipientDatabase.BLOCK + " = 0 AND " +
            GroupDatabase.TABLE_NAME + "." + GROUP_ID + " IS NULL";
    return getConversationList(where);
  }

  private Cursor getConversationList(String where) {
    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
    String         query  = createQuery(where, 0);
    Cursor         cursor = db.rawQuery(query, null);

    setNotifyConversationListListeners(cursor);

    return cursor;
  }

  public Cursor getDirectShareList() {
    SQLiteDatabase db    = databaseHelper.getReadableDatabase();
    String         query = createQuery(MESSAGE_COUNT + " != 0", 0);

    return db.rawQuery(query, null);
  }

  /**
   * @param threadId
   * @param timestamp
   * @return true if we have set the last seen for the thread, false if there were no messages in the thread
   */
  public boolean setLastSeen(long threadId, long timestamp) {
    // edge case where we set the last seen time for a conversation before it loads messages (joining community for example)
    MmsSmsDatabase mmsSmsDatabase = DatabaseComponent.get(context).mmsSmsDatabase();
    Recipient forThreadId = getRecipientForThreadId(threadId);
    if (mmsSmsDatabase.getConversationCount(threadId) <= 0 && forThreadId != null && forThreadId.isCommunityRecipient()) return false;

    SQLiteDatabase db = databaseHelper.getWritableDatabase();

    ContentValues contentValues = new ContentValues(1);
    long lastSeenTime = timestamp == -1 ? SnodeAPI.getNowWithOffset() : timestamp;
    contentValues.put(LAST_SEEN, lastSeenTime);
    db.beginTransaction();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {String.valueOf(threadId)});
    String smsCountSubQuery = "SELECT COUNT(*) FROM "+SmsDatabase.TABLE_NAME+" AS s WHERE t."+ID+" = s."+SmsDatabase.THREAD_ID+" AND s."+SmsDatabase.DATE_SENT+" > t."+LAST_SEEN+" AND s."+SmsDatabase.READ+" = 0";
    String smsMentionCountSubQuery = "SELECT COUNT(*) FROM "+SmsDatabase.TABLE_NAME+" AS s WHERE t."+ID+" = s."+SmsDatabase.THREAD_ID+" AND s."+SmsDatabase.DATE_SENT+" > t."+LAST_SEEN+" AND s."+SmsDatabase.READ+" = 0 AND s."+SmsDatabase.HAS_MENTION+" = 1";
    String smsReactionCountSubQuery = "SELECT COUNT(*) FROM "+SmsDatabase.TABLE_NAME+" AS s WHERE t."+ID+" = s."+SmsDatabase.THREAD_ID+" AND s."+SmsDatabase.DATE_SENT+" > t."+LAST_SEEN+" AND s."+SmsDatabase.REACTIONS_UNREAD+" = 1";
    String mmsCountSubQuery = "SELECT COUNT(*) FROM "+MmsDatabase.TABLE_NAME+" AS m WHERE t."+ID+" = m."+MmsDatabase.THREAD_ID+" AND m."+MmsDatabase.DATE_SENT+" > t."+LAST_SEEN+" AND m."+MmsDatabase.READ+" = 0";
    String mmsMentionCountSubQuery = "SELECT COUNT(*) FROM "+MmsDatabase.TABLE_NAME+" AS m WHERE t."+ID+" = m."+MmsDatabase.THREAD_ID+" AND m."+MmsDatabase.DATE_SENT+" > t."+LAST_SEEN+" AND m."+MmsDatabase.READ+" = 0 AND m."+MmsDatabase.HAS_MENTION+" = 1";
    String mmsReactionCountSubQuery = "SELECT COUNT(*) FROM "+MmsDatabase.TABLE_NAME+" AS m WHERE t."+ID+" = m."+MmsDatabase.THREAD_ID+" AND m."+MmsDatabase.DATE_SENT+" > t."+LAST_SEEN+" AND m."+MmsDatabase.REACTIONS_UNREAD+" = 1";
    String allSmsUnread = "(("+smsCountSubQuery+") + ("+smsReactionCountSubQuery+"))";
    String allMmsUnread = "(("+mmsCountSubQuery+") + ("+mmsReactionCountSubQuery+"))";
    String allUnread = "(("+allSmsUnread+") + ("+allMmsUnread+"))";
    String allUnreadMention = "(("+smsMentionCountSubQuery+") + ("+mmsMentionCountSubQuery+"))";

    String reflectUpdates = "UPDATE "+TABLE_NAME+" AS t SET "+UNREAD_COUNT+" = "+allUnread+", "+UNREAD_MENTION_COUNT+" = "+allUnreadMention+" WHERE "+ID+" = ?";
    db.execSQL(reflectUpdates, new Object[]{threadId});
    db.setTransactionSuccessful();
    db.endTransaction();
    notifyConversationListeners(threadId);
    notifyConversationListListeners();
    return true;
  }

  /**
   * @param threadId
   * @return true if we have set the last seen for the thread, false if there were no messages in the thread
   */
  public boolean setLastSeen(long threadId) {
    return setLastSeen(threadId, -1);
  }

  public Pair<Long, Boolean> getLastSeenAndHasSent(long threadId) {
    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
    Cursor         cursor = db.query(TABLE_NAME, new String[]{LAST_SEEN, HAS_SENT}, ID_WHERE, new String[]{String.valueOf(threadId)}, null, null, null);

    try {
      if (cursor != null && cursor.moveToFirst()) {
        return new Pair<>(cursor.getLong(0), cursor.getLong(1) == 1);
      }

      return new Pair<>(-1L, false);
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public Long getLastUpdated(long threadId) {
    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
    Cursor         cursor = db.query(TABLE_NAME, new String[]{THREAD_CREATION_DATE}, ID_WHERE, new String[]{String.valueOf(threadId)}, null, null, null);

    try {
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getLong(0);
      }

      return -1L;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public int getMessageCount(long threadId) {
    SQLiteDatabase db      = databaseHelper.getReadableDatabase();
    String[]       columns = new String[]{MESSAGE_COUNT};
    String[]       args    = new String[]{String.valueOf(threadId)};
    try (Cursor cursor = db.query(TABLE_NAME, columns, ID_WHERE, args, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getInt(0);
      }

      return 0;
    }
  }

  public void deleteConversation(long threadId) {
    DatabaseComponent.get(context).smsDatabase().deleteThread(threadId);
    DatabaseComponent.get(context).mmsDatabase().deleteThread(threadId);
    DatabaseComponent.get(context).draftDatabase().clearDrafts(threadId);
    DatabaseComponent.get(context).lokiMessageDatabase().deleteThread(threadId);
    deleteThread(threadId);
    notifyConversationListeners(threadId);
    notifyConversationListListeners();
    SessionMetaProtocol.clearReceivedMessages();
  }

  public long getThreadIdIfExistsFor(String address) {
    SQLiteDatabase db      = databaseHelper.getReadableDatabase();
    String where           = ADDRESS + " = ?";
    String[] recipientsArg = new String[] {address};
    Cursor cursor          = null;

    try {
      cursor = db.query(TABLE_NAME, new String[]{ID}, where, recipientsArg, null, null, null);

      if (cursor != null && cursor.moveToFirst())
        return cursor.getLong(cursor.getColumnIndexOrThrow(ID));
      else
        return -1L;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public long getThreadIdIfExistsFor(Recipient recipient) {
    return getThreadIdIfExistsFor(recipient.getAddress().serialize());
  }

  public long getOrCreateThreadIdFor(Recipient recipient) {
    return getOrCreateThreadIdFor(recipient, DistributionTypes.DEFAULT);
  }

  public void setThreadArchived(long threadId) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(ARCHIVED, 1);

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, ID_WHERE,
            new String[] {String.valueOf(threadId)});

    notifyConversationListListeners();
    notifyConversationListeners(threadId);
  }

  public long getOrCreateThreadIdFor(Recipient recipient, int distributionType) {
    SQLiteDatabase db            = databaseHelper.getReadableDatabase();
    String         where         = ADDRESS + " = ?";
    String[]       recipientsArg = new String[]{recipient.getAddress().serialize()};
    Cursor         cursor        = null;

    try {
      cursor = db.query(TABLE_NAME, new String[]{ID}, where, recipientsArg, null, null, null);
      long threadId;
      boolean created = false;
      if (cursor != null && cursor.moveToFirst()) {
        threadId = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
      } else {
        DatabaseComponent.get(context).recipientDatabase().setProfileSharing(recipient, true);
        threadId = createThreadForRecipient(recipient.getAddress(), recipient.isGroupRecipient(), distributionType);
        created = true;
      }
      if (created && updateListener != null) {
        updateListener.threadCreated(recipient.getAddress(), threadId);
      }
      return threadId;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public @Nullable Recipient getRecipientForThreadId(long threadId) {
    if (addressCache.containsKey(threadId) && addressCache.get(threadId) != null) {
      return Recipient.from(context, addressCache.get(threadId), false);
    }

    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor     = null;

    try {
      cursor = db.query(TABLE_NAME, null, ID + " = ?", new String[] {threadId+""}, null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        Address address = Address.fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS)));
        addressCache.put(threadId, address);
        return Recipient.from(context, address, false);
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }

    return null;
  }

  public void setHasSent(long threadId, boolean hasSent) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(HAS_SENT, hasSent ? 1 : 0);

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, ID_WHERE,
            new String[] {String.valueOf(threadId)});

    notifyConversationListeners(threadId);
    notifyConversationListListeners();
  }

  public boolean update(long threadId, boolean unarchive) {
    MmsSmsDatabase mmsSmsDatabase = DatabaseComponent.get(context).mmsSmsDatabase();
    long count                    = mmsSmsDatabase.getConversationCount(threadId);

    try (MmsSmsDatabase.Reader reader = mmsSmsDatabase.readerFor(mmsSmsDatabase.getConversationSnippet(threadId))) {
      MessageRecord record = null;
      if (reader != null) {
        record = reader.getNext();
        while (record != null && record.isDeleted()) {
          record = reader.getNext();
        }
      }
      if (record != null && !record.isDeleted()) {
        updateThread(threadId, count, getFormattedBodyFor(record), getAttachmentUriFor(record),
                record.getTimestamp(), record.getDeliveryStatus(), record.getDeliveryReceiptCount(),
                record.getType(), unarchive, record.getExpiresIn(), record.getReadReceiptCount());
        return false;
      } else {
        // for empty threads or if there is only deleted messages, show an empty snippet
        clearSnippet(threadId);
        return false;
      }
    } finally {
      notifyConversationListListeners();
      notifyConversationListeners(threadId);
    }
  }

  public void setPinned(long threadId, boolean pinned) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(IS_PINNED, pinned ? 1 : 0);

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, ID_WHERE,
            new String[] {String.valueOf(threadId)});

    notifyConversationListeners(threadId);
    notifyConversationListListeners();
  }

  public boolean isPinned(long threadId) {
    SQLiteDatabase db = getReadableDatabase();
    Cursor         cursor = db.query(TABLE_NAME, new String[]{IS_PINNED}, ID_WHERE, new String[]{String.valueOf(threadId)}, null, null, null);
    try {
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getInt(0) == 1;
      }
      return false;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  /**
   * @param threadId
   * @param isGroupRecipient
   * @param lastSeenTime
   * @return true if we have set the last seen for the thread, false if there were no messages in the thread
   */
  public boolean markAllAsRead(long threadId, boolean isGroupRecipient, long lastSeenTime, boolean force) {
    MmsSmsDatabase mmsSmsDatabase = DatabaseComponent.get(context).mmsSmsDatabase();
    if (mmsSmsDatabase.getConversationCount(threadId) <= 0 && !force) return false;
    List<MarkedMessageInfo> messages = setRead(threadId, lastSeenTime);
    MarkReadReceiver.process(context, messages);
    ApplicationContext.getInstance(context).messageNotifier.updateNotification(context, threadId);
    return setLastSeen(threadId, lastSeenTime);
  }

  private @NonNull String getFormattedBodyFor(@NonNull MessageRecord messageRecord) {
    if (messageRecord.isMms()) {
      MmsMessageRecord record = (MmsMessageRecord) messageRecord;
      if (!record.getSharedContacts().isEmpty()) {
        Contact contact = ((MmsMessageRecord)messageRecord).getSharedContacts().get(0);
        return ContactUtil.getStringSummary(context, contact).toString();
      }
      String attachmentString = record.getSlideDeck().getBody();
      if (!attachmentString.isEmpty()) {
        if (!messageRecord.getBody().isEmpty()) {
          attachmentString = attachmentString + ": " + messageRecord.getBody();
        }
        return attachmentString;
      }
    }
    return messageRecord.getBody();
  }

  private @Nullable Uri getAttachmentUriFor(MessageRecord record) {
    if (!record.isMms() || record.isMmsNotification()) return null;

    SlideDeck slideDeck = ((MediaMmsMessageRecord)record).getSlideDeck();
    Slide     thumbnail = slideDeck.getThumbnailSlide();

    if (thumbnail != null) {
      return thumbnail.getThumbnailUri();
    }

    return null;
  }

  private @NonNull String createQuery(@NonNull String where, int limit) {
    String projection = Util.join(COMBINED_THREAD_RECIPIENT_GROUP_PROJECTION, ",");
    String query =
            "SELECT " + projection + " FROM " + TABLE_NAME +
                    " LEFT OUTER JOIN " + RecipientDatabase.TABLE_NAME +
                    " ON " + TABLE_NAME + "." + ADDRESS + " = " + RecipientDatabase.TABLE_NAME + "." + RecipientDatabase.ADDRESS +
                    " LEFT OUTER JOIN " + GroupDatabase.TABLE_NAME +
                    " ON " + TABLE_NAME + "." + ADDRESS + " = " + GroupDatabase.TABLE_NAME + "." + GROUP_ID +
                    " WHERE " + where +
                    " ORDER BY " + TABLE_NAME + "." + IS_PINNED + " DESC, " + TABLE_NAME + "." + THREAD_CREATION_DATE + " DESC";

    if (limit >  0) {
      query += " LIMIT " + limit;
    }

    return query;
  }

  public void migrateEncodedGroup(long threadId, @NotNull String newEncodedGroupId) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(ADDRESS, newEncodedGroupId);
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId+""});
  }

  public void notifyThreadUpdated(long threadId) {
    notifyConversationListeners(threadId);
  }

  public interface ProgressListener {
    void onProgress(int complete, int total);
  }

  public Reader readerFor(Cursor cursor) {
    return new Reader(cursor);
  }

  public class Reader implements Closeable {

    private final Cursor cursor;

    public Reader(Cursor cursor) {
      this.cursor = cursor;
    }

    public int getCount() {
      return cursor == null ? 0 : cursor.getCount();
    }

    public ThreadRecord getNext() {
      if (cursor == null || !cursor.moveToNext())
        return null;

      return getCurrent();
    }

    public ThreadRecord getCurrent() {
      long    threadId         = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.ID));
      int     distributionType = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.DISTRIBUTION_TYPE));
      Address address          = Address.fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.ADDRESS)));

      Optional<RecipientSettings> settings;
      Optional<GroupRecord>       groupRecord;

      if (distributionType != DistributionTypes.ARCHIVE && distributionType != DistributionTypes.INBOX_ZERO) {
        settings    = DatabaseComponent.get(context).recipientDatabase().getRecipientSettings(cursor);
        groupRecord = DatabaseComponent.get(context).groupDatabase().getGroup(cursor);
      } else {
        settings    = Optional.absent();
        groupRecord = Optional.absent();
      }

      Recipient          recipient            = Recipient.from(context, address, settings, groupRecord, true);
      String             body                 = cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET));
      long               date                 = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.THREAD_CREATION_DATE));
      long               count                = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.MESSAGE_COUNT));
      int                unreadCount          = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.UNREAD_COUNT));
      int                unreadMentionCount   = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.UNREAD_MENTION_COUNT));
      long               type                 = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_TYPE));
      boolean            archived             = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.ARCHIVED)) != 0;
      int                status               = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.STATUS));
      int                deliveryReceiptCount = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.DELIVERY_RECEIPT_COUNT));
      int                readReceiptCount     = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.READ_RECEIPT_COUNT));
      long               expiresIn            = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.EXPIRES_IN));
      long               lastSeen             = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.LAST_SEEN));
      Uri                snippetUri           = getSnippetUri(cursor);
      boolean            pinned               = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.IS_PINNED)) != 0;

      if (!TextSecurePreferences.isReadReceiptsEnabled(context)) {
        readReceiptCount = 0;
      }

      MessageRecord lastMessage = null;

      if (count > 0) {
        MmsSmsDatabase mmsSmsDatabase = DatabaseComponent.get(context).mmsSmsDatabase();
        long messageTimestamp = mmsSmsDatabase.getLastMessageTimestamp(threadId);
        if (messageTimestamp > 0) {
          lastMessage = mmsSmsDatabase.getMessageForTimestamp(messageTimestamp);
        }
      }

      return new ThreadRecord(body, snippetUri, lastMessage, recipient, date, count,
              unreadCount, unreadMentionCount, threadId, deliveryReceiptCount, status, type,
              distributionType, archived, expiresIn, lastSeen, readReceiptCount, pinned);
    }

    private @Nullable Uri getSnippetUri(Cursor cursor) {
      if (cursor.isNull(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_URI))) {
        return null;
      }

      try {
        return Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_URI)));
      } catch (IllegalArgumentException e) {
        Log.w(TAG, e);
        return null;
      }
    }

    @Override
    public void close() {
      if (cursor != null) {
        cursor.close();
      }
    }
  }

  // ========================================================================
  //   NUEVOS MÉTODOS: getThreadKeyAlias(...) y setThreadKeyAlias(...)
  //   (Se asume que la columna "thread_key_alias" ya existe en tu tabla
  //    a través de alguna migración)
  // ========================================================================
  /**
   * Retorna la columna thread_key_alias para un hilo dado (threadId).
   *
   * @param threadId ID del hilo
   * @return String con el valor de la columna thread_key_alias o null si no existe
   */
  @Nullable
  public String getThreadKeyAlias(long threadId) {
    SQLiteDatabase db = getReadableDatabase();
    Cursor cursor = null;
    try {
      cursor = db.query(
              TABLE_NAME,
              new String[]{THREAD_KEY_ALIAS},   // buscamos la columna
              ID_WHERE,
              new String[]{String.valueOf(threadId)},
              null,
              null,
              null
      );
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.isNull(0) ? null : cursor.getString(0);
      }
      return null;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  /**
   * Asigna (o borra) la columna thread_key_alias en la tabla 'thread'.
   *
   * @param threadId ID del hilo
   * @param alias Valor para la columna thread_key_alias (o null para limpiarla)
   */
  public void setThreadKeyAlias(long threadId, @Nullable String alias) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(THREAD_KEY_ALIAS, alias);

    getWritableDatabase().update(
            TABLE_NAME,
            contentValues,
            ID_WHERE,
            new String[]{String.valueOf(threadId)}
    );

    notifyConversationListListeners();
    // notifyConversationListeners(threadId); // si fuera necesario
  }

  // ========================================================================
  //   NUEVO MÉTODO: findOrCreateThreadByAlias(...)
  // ========================================================================
  /**
   * Busca en la tabla 'thread' un hilo con thread_key_alias = alias.
   * Si no existe, lo crea e inserta el alias en la columna.
   *
   * @param alias El alias/UUID global de la conversación
   * @return El _id local del hilo en esta tabla
   */
  public long findOrCreateThreadByAlias(@NonNull String alias) {
    if (alias.isEmpty()) {
      throw new IllegalArgumentException("Alias cannot be null or empty!");
    }

    SQLiteDatabase db = getReadableDatabase();
    long existingId = -1;
    Cursor cursor = null;
    try {
      cursor = db.query(
              TABLE_NAME,
              new String[] {ID},
              THREAD_KEY_ALIAS + " = ?",
              new String[]{alias},
              null,
              null,
              null
      );
      if (cursor != null && cursor.moveToFirst()) {
        existingId = cursor.getLong(0);
      }
    } finally {
      if (cursor != null) cursor.close();
    }

    if (existingId >= 0) {
      return existingId;
    }

    // Si no existe => creamos un hilo nuevo
    ContentValues values = new ContentValues();
    values.put(THREAD_KEY_ALIAS, alias);
    // Suele ser conveniente asignar una fecha de creación:
    long date = SnodeAPI.getNowWithOffset();
    values.put(THREAD_CREATION_DATE, date - date % 1000);
    values.put(MESSAGE_COUNT, 0);

    // Insertamos
    long newId = getWritableDatabase().insertOrThrow(TABLE_NAME, null, values);

    // Notificamos si fuera necesario
    notifyConversationListListeners();
    // notifyConversationListeners(newId); // si procede

    return newId;
  }
}

