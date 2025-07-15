/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2013-2017 Open Whisper Systems
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

import static org.session.libsession.utilities.GroupUtil.COMMUNITY_PREFIX;
import static org.session.libsession.utilities.GroupUtil.LEGACY_CLOSED_GROUP_PREFIX;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_ID;
import static org.thoughtcrime.securesms.database.UtilKt.generatePlaceholders;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MergeCursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.session.libsession.messaging.MessagingModuleConfiguration;
import org.session.libsession.snode.SnodeAPI;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.ConfigFactoryProtocolKt;
import org.session.libsession.utilities.DelimiterUtil;
import org.session.libsession.utilities.DistributionTypes;
import org.session.libsession.utilities.GroupRecord;
import org.session.libsession.utilities.TextSecurePreferences;
import org.session.libsession.utilities.Util;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsession.utilities.recipients.Recipient.RecipientSettings;
import org.session.libsignal.utilities.AccountId;
import org.session.libsignal.utilities.IdPrefix;
import org.session.libsignal.utilities.Log;
import org.session.libsignal.utilities.Pair;
import org.session.libsignal.utilities.guava.Optional;
import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.database.model.GroupThreadStatus;
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.database.model.ThreadRecord;
import org.thoughtcrime.securesms.database.model.content.MessageContent;
import org.thoughtcrime.securesms.dependencies.DatabaseComponent;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.mms.SlideDeck;
import org.thoughtcrime.securesms.notifications.MarkReadReceiver;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import kotlin.collections.CollectionsKt;
import kotlinx.serialization.json.Json;
import network.loki.messenger.libsession_util.util.GroupInfo;

@Singleton
public class ThreadDatabase extends Database {

  public interface ConversationThreadUpdateListener {
    void threadCreated(@NonNull Address address, long threadId);
  }

  private static final String TAG = ThreadDatabase.class.getSimpleName();

  // Map of threadID -> Address
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
  /**
   * The column that hold a {@link MessageContent}. See {@link MmsDatabase#MESSAGE_CONTENT} for more information
   */
  public  static final String SNIPPET_CONTENT        = "snippet_content";
  public  static final String ARCHIVED               = "archived";
  public  static final String STATUS                 = "status";
  public  static final String DELIVERY_RECEIPT_COUNT = "delivery_receipt_count";
  public  static final String READ_RECEIPT_COUNT     = "read_receipt_count";
  public  static final String EXPIRES_IN             = "expires_in";
  public  static final String LAST_SEEN              = "last_seen";
  public static final String HAS_SENT                = "has_sent";
  public  static final String IS_PINNED              = "is_pinned";

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

  public static final String ADD_SNIPPET_CONTENT_COLUMN = "ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + SNIPPET_CONTENT + " TEXT DEFAULT NULL;";

  private static final String[] THREAD_PROJECTION = {
      ID, THREAD_CREATION_DATE, MESSAGE_COUNT, ADDRESS, SNIPPET, SNIPPET_CHARSET, READ, UNREAD_COUNT, UNREAD_MENTION_COUNT, DISTRIBUTION_TYPE, ERROR, SNIPPET_TYPE,
      SNIPPET_URI, ARCHIVED, STATUS, DELIVERY_RECEIPT_COUNT, EXPIRES_IN, LAST_SEEN, READ_RECEIPT_COUNT, IS_PINNED, SNIPPET_CONTENT,
  };

  private static final List<String> TYPED_THREAD_PROJECTION = Stream.of(THREAD_PROJECTION)
                                                                    .map(columnName -> TABLE_NAME + "." + columnName)
                                                                    .toList();

  private static final List<String> COMBINED_THREAD_RECIPIENT_GROUP_PROJECTION =
          // wew
          Stream.concat(Stream.concat(Stream.concat(
                  Stream.of(TYPED_THREAD_PROJECTION),
                  Stream.of(RecipientDatabase.TYPED_RECIPIENT_PROJECTION)),
                  Stream.of(GroupDatabase.TYPED_GROUP_PROJECTION)),
                  Stream.of(LokiMessageDatabase.groupInviteTable+"."+LokiMessageDatabase.invitingSessionId)
          )
                                                                                       .toList();

  public static String getCreatePinnedCommand() {
    return "ALTER TABLE "+ TABLE_NAME + " " +
            "ADD COLUMN " + IS_PINNED + " INTEGER DEFAULT 0;";
  }

  public static String getUnreadMentionCountCommand() {
    return "ALTER TABLE "+ TABLE_NAME + " " +
            "ADD COLUMN " + UNREAD_MENTION_COUNT + " INTEGER DEFAULT 0;";
  }

  private ConversationThreadUpdateListener updateListener;
  private final Json json;

  @Inject
  public ThreadDatabase(
          @dagger.hilt.android.qualifiers.ApplicationContext Context context,
          Provider<SQLCipherOpenHelper> databaseHelper,
          TextSecurePreferences prefs,
          Json json) {
    super(context, databaseHelper);
    this.json = json;

    if (!prefs.getMigratedDisappearingMessagesToMessageContent()) {
      migrateDisappearingMessagesToMessageContent();
      prefs.setMigratedDisappearingMessagesToMessageContent(true);
    }
  }

  // As we migrate disappearing messages to MessageContent, we need to ensure that
  // if they appear in the snippet, they have to be re-generated with the new MessageContent.
  private void migrateDisappearingMessagesToMessageContent() {
    String sql = "SELECT " + ID + " FROM " + TABLE_NAME +
            " WHERE " + SNIPPET_TYPE + " & " + MmsSmsColumns.Types.EXPIRATION_TIMER_UPDATE_BIT + " != 0";
    try (final Cursor cursor = getReadableDatabase().rawQuery(sql)) {
      while (cursor.moveToNext()) {
        update(cursor.getLong(0), false);
      }
    }
  }

  public void setUpdateListener(ConversationThreadUpdateListener updateListener) {
    this.updateListener = updateListener;
  }

  private long createThreadForRecipient(Address address, boolean group, int distributionType) {
    ContentValues contentValues = new ContentValues(4);

    contentValues.put(ADDRESS, address.toString());

    if (group) contentValues.put(DISTRIBUTION_TYPE, distributionType);

    contentValues.put(MESSAGE_COUNT, 0);

    SQLiteDatabase db = getWritableDatabase();
    return db.insert(TABLE_NAME, null, contentValues);
  }

  private void updateThread(long threadId, long count, String body, @Nullable Uri attachment, @Nullable MessageContent messageContent,
                            long date, int status, int deliveryReceiptCount, long type, boolean unarchive,
                            long expiresIn, int readReceiptCount)
  {
    ContentValues contentValues = new ContentValues(7);
    contentValues.put(THREAD_CREATION_DATE, date - date % 1000);
    contentValues.put(MESSAGE_COUNT, count);
    if (!body.isEmpty()) {
      contentValues.put(SNIPPET, body);
    }
    contentValues.put(SNIPPET_CONTENT, messageContent == null ? null : json.encodeToString(MessageContent.Companion.serializer(), messageContent));
    contentValues.put(SNIPPET_URI, attachment == null ? null : attachment.toString());
    contentValues.put(SNIPPET_TYPE, type);
    contentValues.put(STATUS, status);
    contentValues.put(DELIVERY_RECEIPT_COUNT, deliveryReceiptCount);
    contentValues.put(READ_RECEIPT_COUNT, readReceiptCount);
    contentValues.put(EXPIRES_IN, expiresIn);

    if (unarchive) { contentValues.put(ARCHIVED, 0); }

    SQLiteDatabase db = getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  public void clearSnippet(long threadId){
    ContentValues contentValues = new ContentValues(1);

    contentValues.put(SNIPPET, "");
    contentValues.put(SNIPPET_CONTENT, "");

    SQLiteDatabase db = getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  public void deleteThread(long threadId) {
    SQLiteDatabase db = getWritableDatabase();
    db.delete(TABLE_NAME, ID_WHERE, new String[] {threadId + ""});
    addressCache.remove(threadId);
    notifyConversationListListeners();
  }

  private void deleteThreads(Set<Long> threadIds) {
    SQLiteDatabase db = getWritableDatabase();
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
    SQLiteDatabase db = getWritableDatabase();
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
        DatabaseComponent.get(context).mmsDatabase().deleteMessagesInThreadBeforeDate(threadId, lastTweetDate, false);

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
    DatabaseComponent.get(context).mmsDatabase().deleteMessagesInThreadBeforeDate(threadId, timestamp, false);
    update(threadId, false);
    notifyConversationListeners(threadId);
  }

  public List<MarkedMessageInfo> setRead(long threadId, long lastReadTime) {

    final List<MarkedMessageInfo> smsRecords = DatabaseComponent.get(context).smsDatabase().setMessagesRead(threadId, lastReadTime);
    final List<MarkedMessageInfo> mmsRecords = DatabaseComponent.get(context).mmsDatabase().setMessagesRead(threadId, lastReadTime);

    ContentValues contentValues = new ContentValues(2);
    contentValues.put(READ, smsRecords.isEmpty() && mmsRecords.isEmpty());
    contentValues.put(LAST_SEEN, lastReadTime);

    SQLiteDatabase db = getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId+""});

    notifyConversationListListeners();

    return CollectionsKt.plus(smsRecords, mmsRecords);
  }

  public List<MarkedMessageInfo> setRead(long threadId, boolean lastSeen) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(READ, 1);
    contentValues.put(UNREAD_COUNT, 0);
    contentValues.put(UNREAD_MENTION_COUNT, 0);

    if (lastSeen) {
      contentValues.put(LAST_SEEN, SnodeAPI.getNowWithOffset());
    }

    SQLiteDatabase db = getWritableDatabase();
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

    SQLiteDatabase db = getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  public void setCreationDate(long threadId, long date) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(THREAD_CREATION_DATE, date);
    SQLiteDatabase db = getWritableDatabase();
    int updated = db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId+""});
    if (updated > 0) notifyConversationListListeners();
  }

  public int getDistributionType(long threadId) {
    SQLiteDatabase db     = getReadableDatabase();
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

  public Cursor searchConversationAddresses(String addressQuery, Set<String> excludeAddresses) {
    if (addressQuery == null || addressQuery.isEmpty()) {
      return null;
    }

    SQLiteDatabase db = getReadableDatabase();
    StringBuilder selection = new StringBuilder(TABLE_NAME + "." + ADDRESS + " LIKE ?");

    List<String> selectionArgs = new ArrayList<>();
    selectionArgs.add(addressQuery + "%");

    // Add exclusion for blocked contacts
    if (excludeAddresses != null && !excludeAddresses.isEmpty()) {
      selection.append(" AND ").append(TABLE_NAME).append(".").append(ADDRESS).append(" NOT IN (");

      // Use the helper method to generate placeholders
      selection.append(generatePlaceholders(excludeAddresses.size()));
      selection.append(")");

      // Add all exclusion addresses to selection args
      selectionArgs.addAll(excludeAddresses);
    }

    String query = createQuery(selection.toString(), 0);
    return db.rawQuery(query, selectionArgs.toArray(new String[0]));
  }


  public Cursor getFilteredConversationList(@Nullable List<Address> filter) {
    if (filter == null || filter.size() == 0)
      return null;

    SQLiteDatabase      db                   = getReadableDatabase();
    List<List<Address>> partitionedAddresses = Util.partition(filter, 900);
    List<Cursor>        cursors              = new LinkedList<>();

    for (List<Address> addresses : partitionedAddresses) {
      StringBuilder selection = new StringBuilder(TABLE_NAME + "." + ADDRESS + " = ?");
      String[] selectionArgs = new String[addresses.size()];

      for (int i = 0; i < addresses.size() - 1; i++) {
        selection.append(" OR " + TABLE_NAME + "." + ADDRESS + " = ?");
      }

      int i= 0;
      for (Address address : addresses) {
        selectionArgs[i++] = DelimiterUtil.escape(address.toString(), ' ');
      }

      String query = createQuery(selection.toString(), 0);
      cursors.add(db.rawQuery(query, selectionArgs));
    }

    Cursor cursor = cursors.size() > 1 ? new MergeCursor(cursors.toArray(new Cursor[0])) : cursors.get(0);
    setNotifyConversationListListeners(cursor);
    return cursor;
  }

  public Cursor getRecentConversationList(int limit) {
    SQLiteDatabase db    = getReadableDatabase();
    String         query = createQuery("", limit);

    return db.rawQuery(query, null);
  }

  public Cursor getConversationList() {
    return getConversationList(ARCHIVED + " = 0 ");
  }

  public Cursor getBlindedConversationList() {
    String where  = TABLE_NAME + "." + ADDRESS + " LIKE '" + IdPrefix.BLINDED.getValue() + "%' ";
    return getConversationList(where);
  }

  public Cursor getApprovedConversationList() {
    String where  = "((" + HAS_SENT + " = 1 OR " + RecipientDatabase.APPROVED + " = 1 OR "+ GroupDatabase.TABLE_NAME +"."+GROUP_ID+" LIKE '"+ LEGACY_CLOSED_GROUP_PREFIX +"%') " +
            "OR " + GroupDatabase.TABLE_NAME + "." + GROUP_ID + " LIKE '" + COMMUNITY_PREFIX + "%') " +
            "AND " + ARCHIVED + " = 0 ";
    return getConversationList(where);
  }

  public Cursor getUnapprovedConversationList() {
    String where  = "("+MESSAGE_COUNT + " != 0 OR "+ThreadDatabase.TABLE_NAME+"."+ThreadDatabase.ADDRESS+" LIKE '"+IdPrefix.GROUP.getValue()+"%')" +
            " AND " + ARCHIVED + " = 0 AND " + HAS_SENT + " = 0 AND " +
            RecipientDatabase.TABLE_NAME + "." + RecipientDatabase.APPROVED + " = 0 AND " +
            RecipientDatabase.TABLE_NAME + "." + RecipientDatabase.BLOCK + " = 0 AND " +
            GroupDatabase.TABLE_NAME + "." + GROUP_ID + " IS NULL";
    return getConversationList(where);
  }

  private Cursor getConversationList(String where) {
    SQLiteDatabase db     = getReadableDatabase();
    String         query  = createQuery(where, 0);
    Cursor         cursor = db.rawQuery(query, null);

    setNotifyConversationListListeners(cursor);

    return cursor;
  }

  public Cursor getDirectShareList() {
    SQLiteDatabase db    = getReadableDatabase();
    String         query = createQuery("", 0);

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

    SQLiteDatabase db = getWritableDatabase();

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


  public Pair<Long, Boolean> getLastSeenAndHasSent(long threadId) {
    SQLiteDatabase db     = getReadableDatabase();
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

  public long getLastUpdated(long threadId) {
    SQLiteDatabase db     = getReadableDatabase();
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
    SQLiteDatabase db      = getReadableDatabase();
    String[]       columns = new String[]{MESSAGE_COUNT};
    String[]       args    = new String[]{String.valueOf(threadId)};
    try (Cursor cursor = db.query(TABLE_NAME, columns, ID_WHERE, args, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getInt(0);
      }

      return 0;
    }
  }

  public long getThreadIdIfExistsFor(String address) {
    SQLiteDatabase db      = getReadableDatabase();
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
    return getThreadIdIfExistsFor(recipient.getAddress().toString());
  }

  public long getOrCreateThreadIdFor(Recipient recipient) {
    return getOrCreateThreadIdFor(recipient, DistributionTypes.DEFAULT);
  }

  public void setThreadArchived(long threadId) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(ARCHIVED, 1);

    getWritableDatabase().update(TABLE_NAME, contentValues, ID_WHERE,
            new String[] {String.valueOf(threadId)});

    notifyConversationListListeners();
    notifyConversationListeners(threadId);
  }

  public long getOrCreateThreadIdFor(Recipient recipient, int distributionType) {
    SQLiteDatabase db            = getReadableDatabase();
    String         where         = ADDRESS + " = ?";
    String[]       recipientsArg = new String[]{recipient.getAddress().toString()};
    Cursor         cursor        = null;

    boolean created = false;

    try {
        long threadId;

        // The synchronization here makes sure we don't create two threads for the same recipient at the same time
        synchronized (this) {
            cursor = db.query(TABLE_NAME, new String[]{ID}, where, recipientsArg, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
              threadId = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
            } else {
              threadId = createThreadForRecipient(recipient.getAddress(), recipient.isGroupOrCommunityRecipient(), distributionType);
              created = true;
            }
        }

        if (created) {
          DatabaseComponent.get(context).recipientDatabase().setProfileSharing(recipient, true);

          if (updateListener != null) {
              updateListener.threadCreated(recipient.getAddress(), threadId);
          }
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

    SQLiteDatabase db = getReadableDatabase();
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

    getWritableDatabase().update(TABLE_NAME, contentValues, ID_WHERE,
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
        updateThread(threadId, count, getFormattedBodyFor(record), getAttachmentUriFor(record), record.getMessageContent(),
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

    getWritableDatabase().update(TABLE_NAME, contentValues, ID_WHERE,
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
   * @param lastSeenTime
   * @return true if we have set the last seen for the thread, false if there were no messages in the thread
   */
  public boolean markAllAsRead(long threadId, long lastSeenTime, boolean force) {
    MmsSmsDatabase mmsSmsDatabase = DatabaseComponent.get(context).mmsSmsDatabase();
    if (mmsSmsDatabase.getConversationCount(threadId) <= 0 && !force) return false;
    List<MarkedMessageInfo> messages = setRead(threadId, lastSeenTime);
    MarkReadReceiver.process(context, messages);
    ApplicationContext.getInstance(context).getMessageNotifier().updateNotification(context, threadId);
    return setLastSeen(threadId, lastSeenTime);
  }

  private @NonNull String getFormattedBodyFor(@NonNull MessageRecord messageRecord) {
    if (messageRecord.isMms()) {
      MmsMessageRecord record = (MmsMessageRecord) messageRecord;
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
            " LEFT OUTER JOIN " + LokiMessageDatabase.groupInviteTable +
            " ON "+ TABLE_NAME + "." + ID + " = " + LokiMessageDatabase.groupInviteTable+"."+LokiMessageDatabase.invitingSessionId +
            " WHERE " + where +
            " ORDER BY " + TABLE_NAME + "." + IS_PINNED + " DESC, " + TABLE_NAME + "." + THREAD_CREATION_DATE + " DESC";

    if (limit >  0) {
      query += " LIMIT " + limit;
    }

    return query;
  }

  public void notifyThreadUpdated(long threadId) {
    notifyConversationListeners(threadId);
  }

  public interface ProgressListener {
    void onProgress(int complete, int total);
  }

  public Reader readerFor(Cursor cursor) {
    return readerFor(cursor, true);
  }

  /**
   * Create a reader to conveniently access the thread cursor
   *
   * @param retrieveGroupStatus Whether group status should be calculated based on the config data.
   *                            Normally you always want it, but if you don't want the reader
   *                            to access the config system, this is the flag to turn it off.
   */
  public Reader readerFor(Cursor cursor, boolean retrieveGroupStatus) {
    return new Reader(cursor, retrieveGroupStatus);
  }

  public class Reader implements Closeable {

    private final Cursor cursor;
    private final boolean retrieveGroupStatus;

    public Reader(Cursor cursor, boolean retrieveGroupStatus) {
      this.cursor = cursor;
      this.retrieveGroupStatus = retrieveGroupStatus;
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
      boolean            pinned              = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.IS_PINNED)) != 0;
      String             invitingAdmin       = cursor.getString(cursor.getColumnIndexOrThrow(LokiMessageDatabase.invitingSessionId));
      String messageContentJson = cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_CONTENT));

      if (!TextSecurePreferences.isReadReceiptsEnabled(context)) {
        readReceiptCount = 0;
      }

      MessageRecord lastMessage = null;

      if (count > 0) {
        MmsSmsDatabase mmsSmsDatabase = DatabaseComponent.get(context).mmsSmsDatabase();
        lastMessage = mmsSmsDatabase.getLastMessage(threadId);
      }

      final GroupThreadStatus groupThreadStatus;
      if (recipient.isGroupV2Recipient() && retrieveGroupStatus) {
        GroupInfo.ClosedGroupInfo group = ConfigFactoryProtocolKt.getGroup(
                MessagingModuleConfiguration.getShared().getConfigFactory(),
                new AccountId(recipient.getAddress().toString())
        );
        if (group != null && group.getDestroyed()) {
          groupThreadStatus = GroupThreadStatus.Destroyed;
        } else if (group != null && group.getKicked()) {
          groupThreadStatus = GroupThreadStatus.Kicked;
        } else {
          groupThreadStatus = GroupThreadStatus.None;
        }
      } else {
        groupThreadStatus = GroupThreadStatus.None;
      }

        MessageContent messageContent;
        try {
            messageContent = (messageContentJson == null || messageContentJson.isEmpty()) ? null : json.decodeFromString(
                    MessageContent.Companion.serializer(),
                    messageContentJson
            );
        } catch (Exception e) {
          Log.e(TAG, "Failed to parse message content for thread: " + threadId, e);
          messageContent = null;
        }
        return new ThreadRecord(body, snippetUri, lastMessage, recipient, date, count,
                              unreadCount, unreadMentionCount, threadId, deliveryReceiptCount, status, type,
                              distributionType, archived, expiresIn, lastSeen, readReceiptCount, pinned, invitingAdmin, groupThreadStatus, messageContent);
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
}
