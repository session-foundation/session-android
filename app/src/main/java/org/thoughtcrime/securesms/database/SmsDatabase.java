/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2013 - 2017 Open Whisper Systems
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

import static org.thoughtcrime.securesms.database.MmsSmsColumns.Types.GROUP_UPDATE_MESSAGE_BIT;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;


import com.annimon.stream.Stream;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.json.JSONArray;
import net.zetetic.database.sqlcipher.SQLiteStatement;

import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.session.libsession.messaging.calls.CallMessageType;
import org.session.libsession.messaging.messages.signal.IncomingGroupMessage;
import org.session.libsession.messaging.messages.signal.IncomingTextMessage;
import org.session.libsession.messaging.messages.signal.OutgoingTextMessage;
import org.session.libsession.snode.SnodeAPI;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.IdentityKeyMismatch;
import org.session.libsession.utilities.IdentityKeyMismatchList;
import org.session.libsession.utilities.TextSecurePreferences;
import org.session.libsession.utilities.Util;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsignal.utilities.JsonUtil;
import org.session.libsignal.utilities.Log;
import org.session.libsignal.utilities.guava.Optional;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.ReactionRecord;
import org.thoughtcrime.securesms.database.model.SmsMessageRecord;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import dagger.Lazy;
import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Database for storage of SMS messages.
 *
 * @author Moxie Marlinspike
 */
@Singleton
public class SmsDatabase extends MessagingDatabase {

  private static final String TAG = SmsDatabase.class.getSimpleName();

  public  static final String TABLE_NAME         = "sms";
  public  static final String PERSON             = "person";
          static final String DATE_RECEIVED      = "date";
          static final String DATE_SENT          = "date_sent";
  public  static final String PROTOCOL           = "protocol";
  public  static final String STATUS             = "status";
  public  static final String TYPE               = "type";
  public  static final String REPLY_PATH_PRESENT = "reply_path_present";
  public  static final String SUBJECT            = "subject";
  public  static final String SERVICE_CENTER     = "service_center";

  private static final String IS_DELETED_COLUMN_DEF = IS_DELETED + " GENERATED ALWAYS AS ((" + TYPE +
          " & " + Types.BASE_TYPE_MASK + ") IN (" + Types.BASE_DELETED_OUTGOING_TYPE + ", " + Types.BASE_DELETED_INCOMING_TYPE +")) VIRTUAL";

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " (" + ID + " integer PRIMARY KEY, "                +
    THREAD_ID + " INTEGER, " + ADDRESS + " TEXT, " + ADDRESS_DEVICE_ID + " INTEGER DEFAULT 1, " + PERSON + " INTEGER, " +
    DATE_RECEIVED  + " INTEGER, " + DATE_SENT + " INTEGER, " + PROTOCOL + " INTEGER, " + READ + " INTEGER DEFAULT 0, " +
    STATUS + " INTEGER DEFAULT -1," + TYPE + " INTEGER, " + REPLY_PATH_PRESENT + " INTEGER, " +
    DELIVERY_RECEIPT_COUNT + " INTEGER DEFAULT 0," + SUBJECT + " TEXT, " + BODY + " TEXT, " +
    MISMATCHED_IDENTITIES + " TEXT DEFAULT NULL, " + SERVICE_CENTER + " TEXT, " + SUBSCRIPTION_ID + " INTEGER DEFAULT -1, " +
    EXPIRES_IN + " INTEGER DEFAULT 0, " + EXPIRE_STARTED + " INTEGER DEFAULT 0, " + NOTIFIED + " DEFAULT 0, " +
    READ_RECEIPT_COUNT + " INTEGER DEFAULT 0, " + UNIDENTIFIED + " INTEGER DEFAULT 0, " + IS_DELETED_COLUMN_DEF +");";


  public static final String[] CREATE_INDEXS = {
    "CREATE INDEX IF NOT EXISTS sms_thread_id_index ON " + TABLE_NAME + " (" + THREAD_ID + ");",
    "CREATE INDEX IF NOT EXISTS sms_read_index ON " + TABLE_NAME + " (" + READ + ");",
    "CREATE INDEX IF NOT EXISTS sms_read_and_notified_and_thread_id_index ON " + TABLE_NAME + "(" + READ + "," + NOTIFIED + ","  + THREAD_ID + ");",
    "CREATE INDEX IF NOT EXISTS sms_type_index ON " + TABLE_NAME + " (" + TYPE + ");",
    "CREATE INDEX IF NOT EXISTS sms_date_sent_index ON " + TABLE_NAME + " (" + DATE_SENT + ");",
    "CREATE INDEX IF NOT EXISTS sms_thread_date_index ON " + TABLE_NAME + " (" + THREAD_ID + ", " + DATE_RECEIVED + ");"
  };

  private static final String[] MESSAGE_PROJECTION = new String[] {
      ID, THREAD_ID, ADDRESS, ADDRESS_DEVICE_ID, PERSON,
      DATE_RECEIVED + " AS " + NORMALIZED_DATE_RECEIVED,
      DATE_SENT + " AS " + NORMALIZED_DATE_SENT,
      PROTOCOL, READ, STATUS, TYPE,
      REPLY_PATH_PRESENT, SUBJECT, BODY, SERVICE_CENTER, DELIVERY_RECEIPT_COUNT,
      MISMATCHED_IDENTITIES, SUBSCRIPTION_ID, EXPIRES_IN, EXPIRE_STARTED,
      NOTIFIED, READ_RECEIPT_COUNT, HAS_MENTION,
      "json_group_array(json_object(" +
              "'" + ReactionDatabase.ROW_ID + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.ROW_ID + ", " +
              "'" + ReactionDatabase.MESSAGE_ID + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.MESSAGE_ID + ", " +
              "'" + ReactionDatabase.IS_MMS + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.IS_MMS + ", " +
              "'" + ReactionDatabase.AUTHOR_ID + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.AUTHOR_ID + ", " +
              "'" + ReactionDatabase.EMOJI + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.EMOJI + ", " +
              "'" + ReactionDatabase.SERVER_ID + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.SERVER_ID + ", " +
              "'" + ReactionDatabase.COUNT + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.COUNT + ", " +
              "'" + ReactionDatabase.SORT_ID + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.SORT_ID + ", " +
              "'" + ReactionDatabase.DATE_SENT + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.DATE_SENT + ", " +
              "'" + ReactionDatabase.DATE_RECEIVED + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.DATE_RECEIVED +
              ")) AS " + ReactionDatabase.REACTION_JSON_ALIAS
  };

  public static String CREATE_REACTIONS_UNREAD_COMMAND = "ALTER TABLE "+ TABLE_NAME + " " +
          "ADD COLUMN " + REACTIONS_UNREAD + " INTEGER DEFAULT 0;";

  public static String CREATE_HAS_MENTION_COMMAND = "ALTER TABLE "+ TABLE_NAME + " " +
          "ADD COLUMN " + HAS_MENTION + " INTEGER DEFAULT 0;";

  private static String COMMA_SEPARATED_COLUMNS = ID + ", " + THREAD_ID + ", " + ADDRESS + ", " + ADDRESS_DEVICE_ID + ", " + PERSON + ", " + DATE_RECEIVED + ", " + DATE_SENT + ", " + PROTOCOL + ", " + READ + ", " + STATUS + ", " + TYPE + ", " + REPLY_PATH_PRESENT + ", " + DELIVERY_RECEIPT_COUNT + ", " + SUBJECT + ", " + BODY + ", " + MISMATCHED_IDENTITIES + ", " + SERVICE_CENTER + ", " + SUBSCRIPTION_ID + ", " + EXPIRES_IN + ", " + EXPIRE_STARTED + ", " + NOTIFIED + ", " + READ_RECEIPT_COUNT + ", " + UNIDENTIFIED + ", " + REACTIONS_UNREAD + ", " + HAS_MENTION;
  private static String TEMP_TABLE_NAME = "TEMP_TABLE_NAME";

  public static final String[] ADD_AUTOINCREMENT = new String[]{
          "ALTER TABLE " + TABLE_NAME + " RENAME TO " + TEMP_TABLE_NAME,
          CREATE_TABLE,
          CREATE_REACTIONS_UNREAD_COMMAND,
          CREATE_HAS_MENTION_COMMAND,
          "INSERT INTO " + TABLE_NAME + " (" + COMMA_SEPARATED_COLUMNS + ") SELECT " + COMMA_SEPARATED_COLUMNS + " FROM " + TEMP_TABLE_NAME,
          "DROP TABLE " + TEMP_TABLE_NAME
  };

  public static final String ADD_IS_DELETED_COLUMN = "ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + IS_DELETED_COLUMN_DEF;
  public static final String ADD_IS_GROUP_UPDATE_COLUMN = "ALTER TABLE " + TABLE_NAME +" ADD COLUMN " + IS_GROUP_UPDATE +" BOOL GENERATED ALWAYS AS (" + TYPE +" & " + GROUP_UPDATE_MESSAGE_BIT +" != 0) VIRTUAL";

  private static final EarlyReceiptCache earlyDeliveryReceiptCache = new EarlyReceiptCache();
  private static final EarlyReceiptCache earlyReadReceiptCache     = new EarlyReceiptCache();

  private final RecipientRepository recipientRepository;
  private final Lazy<@NonNull ThreadDatabase> threadDatabase;
  private final Lazy<@NonNull ReactionDatabase> reactionDatabase;

  @Inject
  public SmsDatabase(@ApplicationContext Context context,
                     Provider<SQLCipherOpenHelper> databaseHelper,
                     RecipientRepository recipientRepository,
                     Lazy<@NonNull ThreadDatabase> threadDatabase,
                     Lazy<@NonNull ReactionDatabase> reactionDatabase) {
    super(context, databaseHelper);
    this.recipientRepository = recipientRepository;
    this.threadDatabase = threadDatabase;
    this.reactionDatabase = reactionDatabase;
  }

  protected String getTableName() {
    return TABLE_NAME;
  }

  private void updateTypeBitmask(long id, long maskOff, long maskOn) {
    Log.i("MessageDatabase", "Updating ID: " + id + " to base type: " + maskOn);

    SQLiteDatabase db = getWritableDatabase();
    db.execSQL("UPDATE " + TABLE_NAME +
               " SET " + TYPE + " = (" + TYPE + " & " + (Types.TOTAL_MASK - maskOff) + " | " + maskOn + " )" +
               " WHERE " + ID + " = ?", new String[] {id+""});

    long threadId = getThreadIdForMessage(id);

    threadDatabase.get().update(threadId, false);
  }

  public long getThreadIdForMessage(long id) {
    String sql        = "SELECT " + THREAD_ID + " FROM " + TABLE_NAME + " WHERE " + ID + " = ?";
    String[] sqlArgs  = new String[] {id+""};
    SQLiteDatabase db = getReadableDatabase();

    Cursor cursor = null;

    try {
      cursor = db.rawQuery(sql, sqlArgs);
      if (cursor != null && cursor.moveToFirst())
        return cursor.getLong(0);
      else
        return -1;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public int getMessageCountForThread(long threadId) {
    SQLiteDatabase db = getReadableDatabase();
    Cursor cursor     = null;

    try {
      cursor = db.query(TABLE_NAME, new String[] {"COUNT(*)"}, THREAD_ID + " = ?",
                        new String[] {threadId+""}, null, null, null);

      if (cursor != null && cursor.moveToFirst())
        return cursor.getInt(0);
    } finally {
      if (cursor != null)
        cursor.close();
    }

    return 0;
  }

  public void markAsDecryptFailed(long id) {
    updateTypeBitmask(id, Types.ENCRYPTION_MASK, Types.ENCRYPTION_REMOTE_FAILED_BIT);
  }

  @Override
  public void markAsSent(long id, boolean isSent) {
    updateTypeBitmask(id, Types.BASE_TYPE_MASK, Types.BASE_SENT_TYPE | (isSent ? Types.PUSH_MESSAGE_BIT | Types.SECURE_MESSAGE_BIT : 0));
  }

  public void markAsSending(long id) {
    updateTypeBitmask(id, Types.BASE_TYPE_MASK, Types.BASE_SENDING_TYPE);
  }

  @Override
  public void markAsSyncing(long id) {
    updateTypeBitmask(id, Types.BASE_TYPE_MASK, Types.BASE_SYNCING_TYPE);
  }

  @Override
  public void markAsResyncing(long id) {
    updateTypeBitmask(id, Types.BASE_TYPE_MASK, Types.BASE_RESYNCING_TYPE);
  }

  @Override
  public void markAsSyncFailed(long id) {
    updateTypeBitmask(id, Types.BASE_TYPE_MASK, Types.BASE_SYNC_FAILED_TYPE);
  }

  @Override
  public void markAsDeleted(long messageId, boolean isOutgoing, String displayedMessage) {
    SQLiteDatabase database     = getWritableDatabase();
    ContentValues contentValues = new ContentValues();
    contentValues.put(READ, 1);
    contentValues.put(BODY, displayedMessage);
    contentValues.put(HAS_MENTION, 0);
    contentValues.put(STATUS, Status.STATUS_NONE);

    database.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {String.valueOf(messageId)});

    updateTypeBitmask(messageId, Types.BASE_TYPE_MASK,
            isOutgoing? MmsSmsColumns.Types.BASE_DELETED_OUTGOING_TYPE : MmsSmsColumns.Types.BASE_DELETED_INCOMING_TYPE
    );
  }

  @Override
  public void markExpireStarted(long id, long startedAtTimestamp) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(EXPIRE_STARTED, startedAtTimestamp);

    SQLiteDatabase db = getWritableDatabase();
    try (final Cursor cursor = db.rawQuery("UPDATE " + TABLE_NAME + " SET " + EXPIRE_STARTED + " = ? " +
                    "WHERE " + ID + " = ? RETURNING " + THREAD_ID, startedAtTimestamp, id)) {
      if (cursor.moveToNext()) {
        long threadId = cursor.getLong(0);
        threadDatabase.get().update(threadId, false);
      }
    }
  }

  public void markAsSentFailed(long id) {
    updateTypeBitmask(id, Types.BASE_TYPE_MASK, Types.BASE_SENT_FAILED_TYPE);
  }

  public void markAsNotified(long id) {
    SQLiteDatabase database      = getWritableDatabase();
    ContentValues  contentValues = new ContentValues();

    contentValues.put(NOTIFIED, 1);

    database.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {String.valueOf(id)});
  }

  public boolean isOutgoingMessage(long id) {
    SQLiteDatabase database     = getWritableDatabase();
    Cursor         cursor       = null;
    boolean        isOutgoing   = false;

    try {
      cursor = database.query(TABLE_NAME, new String[] { ID, THREAD_ID, ADDRESS, TYPE },
               ID + " = ?", new String[] { String.valueOf(id) },
               null, null, null, null);

      while (cursor.moveToNext()) {
        if (Types.isOutgoingMessageType(cursor.getLong(cursor.getColumnIndexOrThrow(TYPE)))) {
          isOutgoing = true;
        }
      }
    } finally {
      if (cursor != null) cursor.close();
    }

    return isOutgoing;
  }

  public boolean isDeletedMessage(long id) {
    SQLiteDatabase database     = getWritableDatabase();
    Cursor         cursor       = null;
    boolean        isDeleted   = false;

    try {
      cursor = database.query(TABLE_NAME, new String[] { ID, THREAD_ID, ADDRESS, TYPE },
              ID + " = ?", new String[] { String.valueOf(id) },
              null, null, null, null);

      while (cursor.moveToNext()) {
        if (Types.isDeletedMessage(cursor.getLong(cursor.getColumnIndexOrThrow(TYPE)))) {
          isDeleted = true;
        }
      }
    } finally {
      if (cursor != null) cursor.close();
    }

    return isDeleted;
  }

  @Override
  public String getTypeColumn() {
    return TYPE;
  }

  public void incrementReceiptCount(SyncMessageId messageId, boolean deliveryReceipt, boolean readReceipt) {
    SQLiteDatabase database     = getWritableDatabase();
    Cursor         cursor       = null;
    boolean        foundMessage = false;

    try {
      cursor = database.query(TABLE_NAME, new String[] {ID, THREAD_ID, ADDRESS, TYPE},
                              DATE_SENT + " = ?", new String[] {String.valueOf(messageId.getTimetamp())},
                              null, null, null, null);

      while (cursor.moveToNext()) {
        if (Types.isOutgoingMessageType(cursor.getLong(cursor.getColumnIndexOrThrow(TYPE)))) {
          Address theirAddress = messageId.getAddress();
          Address ourAddress   = Address.fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS)));
          String  columnName   = deliveryReceipt ? DELIVERY_RECEIPT_COUNT : READ_RECEIPT_COUNT;

          if (ourAddress.equals(theirAddress)) {
            long threadId = cursor.getLong(cursor.getColumnIndexOrThrow(THREAD_ID));

            database.execSQL("UPDATE " + TABLE_NAME +
                             " SET " + columnName + " = " + columnName + " + 1 WHERE " +
                             ID + " = ?",
                             new String[] {String.valueOf(cursor.getLong(cursor.getColumnIndexOrThrow(ID)))});

            threadDatabase.get().update(threadId, false);
            foundMessage = true;
          }
        }
      }

      if (!foundMessage) {
        if (deliveryReceipt) earlyDeliveryReceiptCache.increment(messageId.getTimetamp(), messageId.getAddress());
        if (readReceipt)     earlyReadReceiptCache.increment(messageId.getTimetamp(), messageId.getAddress());
      }

    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public List<MarkedMessageInfo> setMessagesRead(long threadId, long beforeTime) {
    return setMessagesRead(THREAD_ID + " = ? AND (" + READ + " = 0) AND " + DATE_SENT + " <= ?", new String[]{threadId+"", beforeTime+""});
  }
  public List<MarkedMessageInfo> setMessagesRead(long threadId) {
    return setMessagesRead(THREAD_ID + " = ? AND (" + READ + " = 0)", new String[] {String.valueOf(threadId)});
  }

  public List<MarkedMessageInfo> setAllMessagesRead() {
    return setMessagesRead(READ + " = 0", null);
  }

  private List<MarkedMessageInfo> setMessagesRead(String where, String[] arguments) {
    SQLiteDatabase          database  = getWritableDatabase();
    List<MarkedMessageInfo> results   = new LinkedList<>();
    Cursor                  cursor    = null;

    database.beginTransaction();
    try {
      cursor = database.query(TABLE_NAME, new String[] {ID, ADDRESS, DATE_SENT, TYPE, EXPIRES_IN, EXPIRE_STARTED}, where, arguments, null, null, null);

      while (cursor != null && cursor.moveToNext()) {
        long timestamp = cursor.getLong(2);
        SyncMessageId  syncMessageId  = new SyncMessageId(Address.fromSerialized(cursor.getString(1)), timestamp);
        ExpirationInfo expirationInfo = new ExpirationInfo(new MessageId(cursor.getLong(0), false), timestamp, cursor.getLong(4), cursor.getLong(5));

        results.add(new MarkedMessageInfo(syncMessageId, expirationInfo));
      }

      ContentValues contentValues = new ContentValues();
      contentValues.put(READ, 1);
      contentValues.put(REACTIONS_UNREAD, 0);

      database.update(TABLE_NAME, contentValues, where, arguments);
      database.setTransactionSuccessful();
    } finally {
      if (cursor != null) cursor.close();
      database.endTransaction();
    }

    return results;
  }

  public void updateSentTimestamp(long messageId, long newTimestamp) {
    SQLiteDatabase db = getWritableDatabase();
    db.rawQuery("UPDATE " + TABLE_NAME + " SET " + DATE_SENT + " = ? " +
            "WHERE " + ID + " = ?", newTimestamp, messageId);
  }

  protected Optional<InsertResult> insertMessageInbox(IncomingTextMessage message, long type, long serverTimestamp, boolean runThreadUpdate) {
    Address recipient = message.getSender();

    Address groupRecipient;

    if (message.getGroupId() == null) {
      groupRecipient = null;
    } else {
      groupRecipient = message.getGroupId();
    }

    boolean    unread     = (message.isSecureMessage() || message.isGroup() || message.isUnreadCallMessage());

    long       threadId;

    if (groupRecipient == null) threadId = threadDatabase.get().getOrCreateThreadIdFor(recipient);
    else                        threadId = threadDatabase.get().getOrCreateThreadIdFor(groupRecipient);

    if (message.isSecureMessage()) {
      type |= Types.SECURE_MESSAGE_BIT;
    } else if (message.isGroup()) {
      type |= Types.SECURE_MESSAGE_BIT;
      if (((IncomingGroupMessage)message).isUpdateMessage()) type |= GROUP_UPDATE_MESSAGE_BIT;
    }

    if (message.isPush()) type |= Types.PUSH_MESSAGE_BIT;

    if (message.isOpenGroupInvitation()) type |= Types.OPEN_GROUP_INVITATION_BIT;

    CallMessageType callMessageType = message.getCallType();
    if (callMessageType != null) {
      type |= getCallMessageTypeMask(callMessageType);
    }

    ContentValues values = new ContentValues(6);
    values.put(ADDRESS, message.getSender().toString());
    values.put(ADDRESS_DEVICE_ID,  message.getSenderDeviceId());
    // In open groups messages should be sorted by their server timestamp
    long receivedTimestamp = serverTimestamp;
    if (serverTimestamp == 0) { receivedTimestamp = message.getSentTimestampMillis(); }
    values.put(DATE_RECEIVED, receivedTimestamp); // Loki - This is important due to how we handle GIFs
    values.put(DATE_SENT, message.getSentTimestampMillis());
    values.put(PROTOCOL, message.getProtocol());
    values.put(READ, unread ? 0 : 1);
    values.put(SUBSCRIPTION_ID, message.getSubscriptionId());
    values.put(EXPIRES_IN, message.getExpiresIn());
    values.put(EXPIRE_STARTED, message.getExpireStartedAt());
    values.put(UNIDENTIFIED, message.isUnidentified());
    values.put(HAS_MENTION, message.hasMention());

    if (!TextUtils.isEmpty(message.getPseudoSubject()))
      values.put(SUBJECT, message.getPseudoSubject());

    values.put(REPLY_PATH_PRESENT, message.isReplyPathPresent());
    values.put(SERVICE_CENTER, message.getServiceCenterAddress());
    values.put(BODY, message.getMessageBody());
    values.put(TYPE, type);
    values.put(THREAD_ID, threadId);

    if (message.isPush() && isDuplicate(message, threadId)) {
      Log.w(TAG, "Duplicate message (" + message.getSentTimestampMillis() + "), ignoring...");
      return Optional.absent();
    } else {
      SQLiteDatabase db        = getWritableDatabase();
      long           messageId = db.insert(TABLE_NAME, null, values);

      if (runThreadUpdate) {
        threadDatabase.get().update(threadId, true);
      }

      return Optional.of(new InsertResult(messageId, threadId));
    }
  }

  private long getCallMessageTypeMask(CallMessageType callMessageType) {
    switch (callMessageType) {
      case CALL_OUTGOING:
        return Types.OUTGOING_CALL_TYPE;
      case CALL_INCOMING:
        return Types.INCOMING_CALL_TYPE;
      case CALL_MISSED:
        return Types.MISSED_CALL_TYPE;
      case CALL_FIRST_MISSED:
        return Types.FIRST_MISSED_CALL_TYPE;
      default:
        return 0;
    }
  }

  public Optional<InsertResult> insertMessageInbox(IncomingTextMessage message, boolean runThreadUpdate) {
    return insertMessageInbox(message, Types.BASE_INBOX_TYPE, 0, runThreadUpdate);
  }

  public Optional<InsertResult> insertCallMessage(IncomingTextMessage message) {
    return insertMessageInbox(message, 0, 0, true);
  }

  public Optional<InsertResult> insertMessageInbox(IncomingTextMessage message, long serverTimestamp, boolean runThreadUpdate) {
    return insertMessageInbox(message, Types.BASE_INBOX_TYPE, serverTimestamp, runThreadUpdate);
  }

  public Optional<InsertResult> insertMessageOutbox(long threadId, OutgoingTextMessage message, long serverTimestamp, boolean runThreadUpdate) {
    if (threadId == -1) {
      threadId = threadDatabase.get().getOrCreateThreadIdFor(message.getRecipient());
    }
    long messageId = insertMessageOutbox(threadId, message, false, serverTimestamp, runThreadUpdate);
    if (messageId == -1) {
      return Optional.absent();
    }
    markAsSent(messageId, true);
    return Optional.fromNullable(new InsertResult(messageId, threadId));
  }

  public long insertMessageOutbox(long threadId, OutgoingTextMessage message,
                                  boolean forceSms, long date,
                                  boolean runThreadUpdate)
  {
    long type = Types.BASE_SENDING_TYPE;

    if (message.isSecureMessage())       type |= (Types.SECURE_MESSAGE_BIT | Types.PUSH_MESSAGE_BIT);
    if (forceSms)                        type |= Types.MESSAGE_FORCE_SMS_BIT;
    if (message.isOpenGroupInvitation()) type |= Types.OPEN_GROUP_INVITATION_BIT;

    Address            address               = message.getRecipient();
    Map<Address, Long> earlyDeliveryReceipts = earlyDeliveryReceiptCache.remove(date);
    Map<Address, Long> earlyReadReceipts     = earlyReadReceiptCache.remove(date);

    ContentValues contentValues = new ContentValues();
    contentValues.put(ADDRESS, address.toString());
    contentValues.put(THREAD_ID, threadId);
    contentValues.put(BODY, message.getMessageBody());
    contentValues.put(DATE_RECEIVED, SnodeAPI.getNowWithOffset());
    contentValues.put(DATE_SENT, message.getSentTimestampMillis());
    contentValues.put(READ, 1);
    contentValues.put(TYPE, type);
    contentValues.put(SUBSCRIPTION_ID, message.getSubscriptionId());
    contentValues.put(EXPIRES_IN, message.getExpiresIn());
    contentValues.put(EXPIRE_STARTED, message.getExpireStartedAt());
    contentValues.put(DELIVERY_RECEIPT_COUNT, Stream.of(earlyDeliveryReceipts.values()).mapToLong(Long::longValue).sum());
    contentValues.put(READ_RECEIPT_COUNT, Stream.of(earlyReadReceipts.values()).mapToLong(Long::longValue).sum());

    if (isDuplicate(message, threadId)) {
      Log.w(TAG, "Duplicate message (" + message.getSentTimestampMillis() + "), ignoring...");
      return -1;
    }

    SQLiteDatabase db        = getWritableDatabase();
    long           messageId = db.insert(TABLE_NAME, ADDRESS, contentValues);

    if (runThreadUpdate) {
      threadDatabase.get().update(threadId, true);
    }
    long lastSeen = threadDatabase.get().getLastSeenAndHasSent(threadId).first();
    if (lastSeen < message.getSentTimestampMillis()) {
      threadDatabase.get().setLastSeen(threadId, message.getSentTimestampMillis());
    }

    threadDatabase.get().setHasSent(threadId, true);

    return messageId;
  }

  private Cursor rawQuery(@NonNull String where, @Nullable String[] arguments) {
    SQLiteDatabase database = getReadableDatabase();
    return database.rawQuery("SELECT " + Util.join(MESSAGE_PROJECTION, ",") +
            " FROM " + SmsDatabase.TABLE_NAME +  " LEFT OUTER JOIN " + ReactionDatabase.TABLE_NAME +
            " ON (" + SmsDatabase.TABLE_NAME + "." + SmsDatabase.ID + " = " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.MESSAGE_ID + " AND " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.IS_MMS + " = 0)" +
            " WHERE " + where + " GROUP BY " + SmsDatabase.TABLE_NAME + "." + SmsDatabase.ID, arguments);
  }

  @Override
  public List<Long> getExpiredMessageIDs(long nowMills) {
    String query = "SELECT " + ID + " FROM " + TABLE_NAME +
            " WHERE " + EXPIRES_IN + " > 0 AND " + EXPIRE_STARTED + " > 0 AND " + EXPIRE_STARTED + " + " + EXPIRES_IN + " <= ?";

    try (final Cursor cursor = getReadableDatabase().rawQuery(query, nowMills)) {
      List<Long> result = new ArrayList<>(cursor.getCount());
      while (cursor.moveToNext()) {
          result.add(cursor.getLong(0));
      }

      return result;
    }
  }

  /**
   * @return the next expiring timestamp for messages that have started expiring. 0 if no messages are expiring.
   */
  @Override
  public long getNextExpiringTimestamp() {
    String query = "SELECT MIN(" + EXPIRE_STARTED + " + " + EXPIRES_IN + ") FROM " + TABLE_NAME +
            " WHERE " + EXPIRES_IN + " > 0 AND " + EXPIRE_STARTED + " > 0";

    try (final Cursor cursor = getReadableDatabase().rawQuery(query)) {
      if (cursor.moveToFirst()) {
        return cursor.getLong(0);
      } else {
        return 0L;
      }
    }
  }

  @NonNull
  public SmsMessageRecord getMessage(long messageId) throws NoSuchMessageException {
    final SmsMessageRecord record = getMessageOrNull(messageId);

    if (record == null) throw new NoSuchMessageException("No message for ID: " + messageId);
    else                return record;
  }

  @Nullable
  public SmsMessageRecord getMessageOrNull(long messageId) {
    try (final Cursor cursor = rawQuery(ID_WHERE, new String[]{String.valueOf(messageId)})) {
      return new Reader(cursor).getNext();
    }
  }

  @Override
  public void deleteMessage(long messageId) {
    doDeleteMessages(true, ID + " = ?", messageId);
  }

  @Override
  public void deleteMessages(Collection<Long> messageIds) {doDeleteMessages(true,
            ID + " IN (SELECT value FROM json_each(?))",
            new JSONArray(messageIds).toString()
    );
  }

  @Override
  public void updateThreadId(long fromId, long toId) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(MmsSmsColumns.THREAD_ID, toId);

    SQLiteDatabase db = getWritableDatabase();
    db.update(TABLE_NAME, contentValues, THREAD_ID + " = ?", new String[] {fromId + ""});
  }

  @Override
  public MessageRecord getMessageRecord(long messageId) throws NoSuchMessageException {
    return getMessage(messageId);
  }

  private boolean isDuplicate(IncomingTextMessage message, long threadId) {
    SQLiteDatabase database = getReadableDatabase();
    Cursor         cursor   = database.query(TABLE_NAME, null, DATE_SENT + " = ? AND " + ADDRESS + " = ? AND " + THREAD_ID + " = ?",
                                             new String[]{String.valueOf(message.getSentTimestampMillis()), message.getSender().toString(), String.valueOf(threadId)},
                                             null, null, null, "1");

    try {
      return cursor != null && cursor.moveToFirst();
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  private boolean isDuplicate(OutgoingTextMessage message, long threadId) {
    SQLiteDatabase database = getReadableDatabase();
    Cursor         cursor   = database.query(TABLE_NAME, null, DATE_SENT + " = ? AND " + ADDRESS + " = ? AND " + THREAD_ID + " = ?",
            new String[]{String.valueOf(message.getSentTimestampMillis()), message.getRecipient().toString(), String.valueOf(threadId)},
            null, null, null, "1");

    try {
      return cursor != null && cursor.moveToFirst();
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  private boolean doDeleteMessages(final boolean updateThread, @NonNull final String where, @Nullable final Object...args) {
    final String sql = "DELETE FROM " + TABLE_NAME + " WHERE " + where + " RETURNING " + THREAD_ID;
    final HashSet<Long> deletedMessageThreadIds = new HashSet<>();

    try (final Cursor cursor = getWritableDatabase().rawQuery(sql, args)) {
        while (cursor.moveToNext()) {
            final long threadId = cursor.getLong(0);
            deletedMessageThreadIds.add(threadId);
        }
    }

    if (updateThread) {
      for (final long threadId : deletedMessageThreadIds) {
        threadDatabase.get().update(threadId, false);
      }
    }

    return !deletedMessageThreadIds.isEmpty();
  }

  void deleteMessagesFrom(long threadId, String fromUser) {
    doDeleteMessages(
        true,
        THREAD_ID + " = ? AND " + ADDRESS + " = ?",
        threadId, fromUser
    );
  }

  void deleteMessagesInThreadBeforeDate(long threadId, long date) {
    doDeleteMessages(true, THREAD_ID + " = ? AND " + DATE_SENT + " < ?", threadId, date);
  }

  void deleteThread(long threadId) {
    doDeleteMessages(true, THREAD_ID + " = ?", threadId);
  }

  public void deleteThreads(@NonNull Collection<Long> threadIds, boolean updateThreads) {
    doDeleteMessages(updateThreads, THREAD_ID + " IN (SELECT value FROM json_each(?))",
            new JSONArray(threadIds).toString());
  }


  public static class Status {
    public static final int STATUS_NONE     = -1;
    public static final int STATUS_COMPLETE  = 0;
    public static final int STATUS_PENDING   = 0x20;
    public static final int STATUS_FAILED    = 0x40;
  }

  public Reader readerFor(Cursor cursor) {
    return new Reader(cursor);
  }

  public class Reader implements Closeable {

    private final Cursor cursor;

    public Reader(Cursor cursor) {
      this.cursor = cursor;
    }

    public SmsMessageRecord getNext() {
      if (cursor == null || !cursor.moveToNext())
        return null;

      return getCurrent();
    }

    public int getCount() {
      if (cursor == null) return 0;
      else                return cursor.getCount();
    }

    public SmsMessageRecord getCurrent() {
      long    messageId            = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.ID));
      Address address              = Address.fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabase.ADDRESS)));
      int     addressDeviceId      = cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabase.ADDRESS_DEVICE_ID));
      long    type                 = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.TYPE));
      long    dateReceived         = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.NORMALIZED_DATE_RECEIVED));
      long    dateSent             = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.NORMALIZED_DATE_SENT));
      long    threadId             = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.THREAD_ID));
      int     status               = cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabase.STATUS));
      int     deliveryReceiptCount = cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabase.DELIVERY_RECEIPT_COUNT));
      int     readReceiptCount     = cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabase.READ_RECEIPT_COUNT));
      String  mismatchDocument     = cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabase.MISMATCHED_IDENTITIES));
      int     subscriptionId       = cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabase.SUBSCRIPTION_ID));
      long    expiresIn            = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.EXPIRES_IN));
      long    expireStarted        = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.EXPIRE_STARTED));
      String  body                 = cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabase.BODY));
      boolean hasMention           = cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabase.HAS_MENTION)) == 1;

      if (!TextSecurePreferences.isReadReceiptsEnabled(context)) {
        readReceiptCount = 0;
      }

      List<IdentityKeyMismatch> mismatches = getMismatches(mismatchDocument);
      Recipient recipient  = recipientRepository.getRecipientSync(address);
      List<ReactionRecord>      reactions  = reactionDatabase.get().getReactions(cursor);

      return new SmsMessageRecord(messageId, body, recipient,
                                  recipient,
                                  dateSent, dateReceived, deliveryReceiptCount, type,
                                  threadId, status, mismatches,
                                  expiresIn, expireStarted, readReceiptCount, reactions, hasMention);
    }

    private List<IdentityKeyMismatch> getMismatches(String document) {
      try {
        if (!TextUtils.isEmpty(document)) {
          return JsonUtil.fromJson(document, IdentityKeyMismatchList.class).getList();
        }
      } catch (IOException e) {
        Log.w(TAG, e);
      }

      return new LinkedList<>();
    }

    @Override
    public void close() {
      if (cursor != null) {
        cursor.close();
      }
    }
  }

}
