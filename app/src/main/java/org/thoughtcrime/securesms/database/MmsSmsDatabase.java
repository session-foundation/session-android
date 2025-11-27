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
package org.thoughtcrime.securesms.database;

import static org.thoughtcrime.securesms.database.MmsDatabase.MESSAGE_BOX;
import static org.thoughtcrime.securesms.database.MmsSmsColumns.ID;
import static org.thoughtcrime.securesms.database.MmsSmsColumns.NOTIFIED;
import static org.thoughtcrime.securesms.database.MmsSmsColumns.READ;
import static org.thoughtcrime.securesms.database.MmsSmsColumns.THREAD_ID;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.session.libsession.messaging.utilities.UpdateMessageData;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.GroupUtil;
import org.session.libsignal.utilities.AccountId;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.auth.LoginStateRepository;
import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.MessageRecord;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import dagger.Lazy;
import dagger.hilt.android.qualifiers.ApplicationContext;
import kotlin.Pair;
import kotlin.Triple;
import network.loki.messenger.libsession_util.protocol.ProFeature;
import network.loki.messenger.libsession_util.protocol.ProMessageFeature;

@Singleton
public class MmsSmsDatabase extends Database {

  @SuppressWarnings("unused")
  private static final String TAG = MmsSmsDatabase.class.getSimpleName();

  public static final String TRANSPORT     = "transport_type";
  public static final String MMS_TRANSPORT = "mms";
  public static final String SMS_TRANSPORT = "sms";

  private static final String PROJECTION_ALL = "*";

  private final LoginStateRepository loginStateRepository;
  private final Lazy<@NonNull ThreadDatabase> threadDatabase;
  private final Lazy<@NonNull MmsDatabase> mmsDatabase;
  private final Lazy<@NonNull SmsDatabase> smsDatabase;

  @Inject
  public MmsSmsDatabase(@ApplicationContext Context context,
                        Provider<SQLCipherOpenHelper> databaseHelper,
                        LoginStateRepository loginStateRepository,
                        Lazy<@NonNull ThreadDatabase> threadDatabase, 
                        Lazy<@NonNull MmsDatabase> mmsDatabase, 
                        Lazy<@NonNull SmsDatabase> smsDatabase) {
    super(context, databaseHelper);

    this.loginStateRepository = loginStateRepository;
    this.threadDatabase = threadDatabase;
    this.mmsDatabase = mmsDatabase;
    this.smsDatabase = smsDatabase;
  }

  public @Nullable MessageRecord getMessageForTimestamp(long threadId, long timestamp) {
    final String selection = MmsSmsColumns.NORMALIZED_DATE_SENT + " = " + timestamp +
            " AND " + MmsSmsColumns.THREAD_ID + " = " + threadId;

    try (Cursor cursor = queryTables(PROJECTION_ALL, selection, true, null, null, null)) {
      MmsSmsDatabase.Reader reader = readerFor(cursor);
      return reader.getNext();
    }
  }

  public @Nullable MessageRecord getMessageById(@NonNull MessageId id) {
      String selection = ID + " = " + id.getId() + " AND " +
              TRANSPORT + " = '" + (id.isMms() ? MMS_TRANSPORT : SMS_TRANSPORT) + "'";
      try (MmsSmsDatabase.Reader reader = readerFor(queryTables(PROJECTION_ALL, selection, true, null, null, null))) {
          final MessageRecord messageRecord;
          if ((messageRecord = reader.getNext()) != null) {
            return messageRecord;
          }
      }

    return null;
  }

  public @Nullable MessageRecord getMessageFor(long threadId, long timestamp, String serializedAuthor) {
    return getMessageFor(threadId, timestamp, serializedAuthor, true);
  }

  public @Nullable MessageRecord getMessageFor(long threadId, long timestamp, String serializedAuthor, boolean getQuote) {
    String selection = MmsSmsColumns.NORMALIZED_DATE_SENT + " = " + timestamp + " AND " +
            MmsSmsColumns.THREAD_ID + " = " + threadId;

    try (Cursor cursor = queryTables(PROJECTION_ALL, selection, true, null, null, null)) {
      MmsSmsDatabase.Reader reader = readerFor(cursor, getQuote);

      MessageRecord messageRecord;
      boolean isOwnNumber = serializedAuthor.equals(loginStateRepository.getLocalNumber());

      while ((messageRecord = reader.getNext()) != null) {
        if ((isOwnNumber && messageRecord.isOutgoing()) ||
                (!isOwnNumber && messageRecord.getIndividualRecipient().getAddress().toString().equals(serializedAuthor)))
        {
          return messageRecord;
        }
      }
    }

    return null;
  }

  /**
   * @deprecated We shouldn't be querying messages by timestamp alone. Use `getMessageFor` when possible
   */
  @Deprecated(forRemoval = true)
  public @Nullable MessageRecord getMessageByTimestamp(long timestamp, String serializedAuthor, boolean getQuote) {
    try (Cursor cursor = queryTables(PROJECTION_ALL, MmsSmsColumns.NORMALIZED_DATE_SENT + " = " + timestamp, true, null, null, null)) {
      MmsSmsDatabase.Reader reader = readerFor(cursor, getQuote);

      MessageRecord messageRecord;
      boolean isOwnNumber = serializedAuthor.equals(loginStateRepository.getLocalNumber());

      while ((messageRecord = reader.getNext()) != null) {
        if ((isOwnNumber && messageRecord.isOutgoing()) ||
                (!isOwnNumber && messageRecord.getIndividualRecipient().getAddress().toString().equals(serializedAuthor)))
        {
          return messageRecord;
        }
      }
    }

    return null;
  }

  @Nullable
  public MessageId getLastSentMessageID(long threadId) {
    String order = MmsSmsColumns.NORMALIZED_DATE_SENT + " DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId + " AND NOT " + MmsSmsColumns.IS_DELETED;

    try (final Cursor cursor = queryTables(PROJECTION_ALL, selection, true, null, order, null)) {
      try (MmsSmsDatabase.Reader reader = readerFor(cursor)) {
        MessageRecord messageRecord;
        while ((messageRecord = reader.getNext()) != null) {
          if (messageRecord.isOutgoing()) {
            return new MessageId(messageRecord.getId(), messageRecord.isMms());
          }
        }
      }
    }

    return null;
  }

  public @Nullable MessageRecord getMessageFor(long threadId, long timestamp, Address author) {
    return getMessageFor(threadId, timestamp, author.toString());
  }

  public Cursor getConversation(long threadId, boolean reverse, long offset, long limit) {
    String order     = MmsSmsColumns.NORMALIZED_DATE_SENT + (reverse ? " DESC" : " ASC");
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;
    String limitStr  = limit > 0 || offset > 0 ? offset + ", " + limit : null;

    return queryTables(PROJECTION_ALL, selection, true, null, order, limitStr);
  }

  public Cursor getConversation(long threadId, boolean reverse) {
    return getConversation(threadId, reverse, 0, 0);
  }

  public Cursor getConversationSnippet(long threadId) {
    String order     = MmsSmsColumns.NORMALIZED_DATE_SENT + " DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;

    return queryTables(PROJECTION_ALL, selection, true, null, order, null);
  }

  public List<String> getRecentChatMemberAddresses(long threadId, int limit) {
    String projection = "DISTINCT " + MmsSmsColumns.ADDRESS;
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;
    String order = MmsSmsColumns.NORMALIZED_DATE_SENT + " DESC";
    String limitStr = String.valueOf(limit);

    try (Cursor cursor = queryTables(projection, selection, true, null, order, limitStr)) {
      List<String> addresses = new ArrayList<>();
      while (cursor != null && cursor.moveToNext()) {
        String address = cursor.getString(0);
        if (address != null && !address.isEmpty()) {
          addresses.add(address);
        }
      }
      return addresses;
    }
  }

  public List<MessageRecord> getUserMessages(long threadId, String sender) {

    List<MessageRecord> idList = new ArrayList<>();

    try (Cursor cursor = getConversation(threadId, false)) {
      Reader reader = readerFor(cursor);
      while (reader.getNext() != null) {
        MessageRecord record = reader.getCurrent();
        if (record.getIndividualRecipient().getAddress().toString().equals(sender)) {
          idList.add(record);
        }
      }
    }

    return idList;
  }

  // Builds up and returns a list of all all the messages sent by this user in the given thread.
  // Used to do a pass through our local database to remove records when a user has "Ban & Delete"
  // called on them in a Community.
  public Set<MessageRecord> getAllMessageRecordsFromSenderInThread(long threadId, String serializedAuthor) {
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId + " AND " + MmsSmsColumns.ADDRESS + " = \"" + serializedAuthor + "\"";
    Set<MessageRecord> identifiedMessages = new HashSet<MessageRecord>();

    // Try everything with resources so that they auto-close on end of scope
    try (Cursor cursor = queryTables(PROJECTION_ALL, selection, true, null, null, null)) {
      try (MmsSmsDatabase.Reader reader = readerFor(cursor)) {
        MessageRecord messageRecord;
        while ((messageRecord = reader.getNext()) != null) {
          identifiedMessages.add(messageRecord);
        }
      }
    }
    return identifiedMessages;
  }

  public List<Pair<MessageRecord, String>> getAllMessageRecordsBefore(long threadId, long timestampMills) {
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId + " AND " + MmsSmsColumns.NORMALIZED_DATE_SENT + " < " + timestampMills;
    List<Pair<MessageRecord, String>> identifiedMessages = new ArrayList<>();

    // Try everything with resources so that they auto-close on end of scope
    try (Cursor cursor = queryTables(PROJECTION_ALL, selection, true, null, null, null)) {
      try (MmsSmsDatabase.Reader reader = readerFor(cursor)) {
        MessageRecord messageRecord;
        while ((messageRecord = reader.getNext()) != null) {
          @Nullable String hash =
                  cursor.getString(cursor.getColumnIndexOrThrow(MmsSmsColumns.SERVER_HASH));

          identifiedMessages.add(new Pair<>(messageRecord, hash));
        }
      }
    }
    return identifiedMessages;
  }

  public List<Pair<MessageRecord, String>> getAllMessagesWithHash(long threadId) {

    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;
    List<Pair<MessageRecord, String>> identifiedMessages = new ArrayList<>();

    try (Cursor cursor = queryTables(PROJECTION_ALL, selection, true, null, null, null);
         MmsSmsDatabase.Reader reader = readerFor(cursor)) {

      MessageRecord record;
      while ((record = reader.getNext()) != null) {
        @Nullable String hash =
                cursor.getString(cursor.getColumnIndexOrThrow(MmsSmsColumns.SERVER_HASH));

        identifiedMessages.add(new Pair<>(record, hash));
      }
    }
    return identifiedMessages;
  }

  /**
   * @param includeReactions Whether to query reactions as well.
   */
  @Nullable
  public MessageRecord getLastMessage(long threadId, boolean includeReactions, boolean getQuote) {
    String order     = MmsSmsColumns.NORMALIZED_DATE_SENT + " DESC";
    // make sure the last message isn't marked as deleted
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId + " AND " +
            "NOT " + MmsSmsColumns.IS_DELETED;

    try (Cursor cursor = queryTables(PROJECTION_ALL, selection, includeReactions, null, order, "1")) {
      return readerFor(cursor, getQuote).getNext();
    }
  }

  /**
   * Get the maximum timestamp in a thread up to (and including) the message with the given ID.
   * Useful for determining the last read timestamp in a thread.
   * <p>
   * This method will also consider the reactions associated with messages in the thread.
   * If a reaction has a timestamp greater than the message timestamp, it will be taken into account.
   *
   * @param messageId The message ID up to which to search.
   * @return A pair of maximum timestamp in mills and thread ID, or null if no messages are found.
   */
  @Nullable
  public Pair<Long, Long> getMaxTimestampInThreadUpTo(@NonNull final MessageId messageId) {
    Pair<String, Object[]> query = MmsSmsDatabaseSQLKt.buildMaxTimestampInThreadUpToQuery(messageId);
    try (Cursor cursor = getReadableDatabase().rawQuery(query.getFirst(), query.getSecond())) {
      if (cursor != null && cursor.moveToFirst()) {
        return new Pair<>(cursor.getLong(0), cursor.getLong(1));
      } else {
        return null;
      }
    }
  }

  private String buildOutgoingConditionForNotifications() {
    return "(" + TRANSPORT + " = '" + MMS_TRANSPORT + "' AND " +
            "(" + MESSAGE_BOX + " & " + MmsSmsColumns.Types.BASE_TYPE_MASK + ") IN (" + buildOutgoingTypesList() + "))" +
            " OR " +
            "(" + TRANSPORT + " = '" + SMS_TRANSPORT + "' AND " +
            "(" + SmsDatabase.TYPE + " & " + MmsSmsColumns.Types.BASE_TYPE_MASK + ") IN (" + buildOutgoingTypesList() + "))";
  }

  public Cursor getUnreadIncomingForNotifications(int maxRows) {
    String outgoing = buildOutgoingConditionForNotifications();
    String selection = "(" + READ + " = 0 AND " + NOTIFIED + " = 0 AND NOT (" + outgoing + "))";
    String order    = MmsSmsColumns.NORMALIZED_DATE_SENT + " DESC";
    String limitStr = maxRows > 0 ? String.valueOf(maxRows) : null;
    return queryTables(PROJECTION_ALL, selection, true, null, order, limitStr);
  }

  public Cursor getOutgoingWithUnseenReactionsForNotifications(int maxRows) {
    String outgoing = buildOutgoingConditionForNotifications();
    String lastSeenQuery =
            "SELECT " + ThreadDatabase.LAST_SEEN +
                    " FROM " + ThreadDatabase.TABLE_NAME +
                    " WHERE " + ThreadDatabase.ID + " = " + MmsSmsColumns.THREAD_ID;

    String reactionSelection = ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.DATE_SENT + " IS NOT NULL" +
            " AND " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.DATE_SENT + " > (" + lastSeenQuery + ")";

    String order    = MmsSmsColumns.NORMALIZED_DATE_SENT + " DESC";
    String limitStr = maxRows > 0 ? String.valueOf(maxRows) : null;
    return queryTables(PROJECTION_ALL, outgoing, true, reactionSelection, order, limitStr);
  }

  public Set<Address> getAllReferencedAddresses() {
    final String projection = "DISTINCT " + MmsSmsColumns.ADDRESS;
    final String selection = MmsSmsColumns.ADDRESS + " IS NOT NULL" +
                    " AND " + MmsSmsColumns.ADDRESS + " != ''";

    Set<Address> out = new HashSet<>();
    try (Cursor cursor = queryTables(projection, selection, true, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        String serialized = cursor.getString(0);
        try {
          out.add(Address.fromSerialized(serialized));
        } catch (Exception e) {
          // If parsing fails, skip this row
          Log.w(TAG, "Skipping unparsable address: " + serialized, e);
        }
      }
    }
    return out;
  }

  /** Builds the comma-separated list of base types that represent
   *  *outgoing* messages (same helper as before). */
  private String buildOutgoingTypesList() {
    long[] types = MmsSmsColumns.Types.OUTGOING_MESSAGE_TYPES;
    StringBuilder sb = new StringBuilder(types.length * 3);
    for (int i = 0; i < types.length; i++) {
      if (i > 0) sb.append(',');
      sb.append(types[i]);
    }
    return sb.toString();
  }

  public int getUnreadCount(long threadId) {
    String selection = READ + " = 0 AND " + NOTIFIED + " = 0 AND " + MmsSmsColumns.THREAD_ID + " = " + threadId;

    try (Cursor cursor = queryTables(ID, selection, true, null, null, null)) {
      return cursor != null ? cursor.getCount() : 0;
    }
  }

  public void deleteGroupInfoMessage(AccountId groupId, Class<? extends UpdateMessageData.Kind> kind) {
    long threadId = threadDatabase.get().getThreadIdIfExistsFor(groupId.getHexString());
    if (threadId == -1) {
      Log.d(TAG, "No thread found for group info message deletion");
      return;
    }

    Object[] bindArgs = { threadId, kind.getSimpleName() };

    for (String table : new String[]{ MmsDatabase.TABLE_NAME, SmsDatabase.TABLE_NAME }) {
      // Delete messages with body that is a valid JSON object and .kind.@type == kind
      getWritableDatabase().execSQL(
              "DELETE FROM " + table + " WHERE " + MmsSmsColumns.THREAD_ID + " = ? AND json_valid(" + MmsSmsColumns.BODY + ") AND json_extract(" + MmsSmsColumns.BODY + ", '$.kind.@type') = ?",
              bindArgs
      );
    }
  }

  public long getConversationCount(long threadId) {
    long count = smsDatabase.get().getMessageCountForThread(threadId);
    count    += mmsDatabase.get().getMessageCountForThread(threadId);

    return count;
  }

    public int getOutgoingProFeatureCount(ProFeature feature) {
        long mask = 1L << feature.getBitIndex();

        int smsCount = smsDatabase.get().getOutgoingProFeatureCount(mask);
        int mmsCount = mmsDatabase.get().getOutgoingProFeatureCount(mask);

        return smsCount + mmsCount;
    }

  public void incrementReadReceiptCount(SyncMessageId syncMessageId, long timestamp) {
    smsDatabase.get().incrementReceiptCount(syncMessageId, false, true);
    mmsDatabase.get().incrementReceiptCount(syncMessageId, timestamp, false, true);
  }

  public int getMessagePositionInConversation(long threadId, long sentTimestamp, @NonNull Address address, boolean reverse) {
    String order     = MmsSmsColumns.NORMALIZED_DATE_SENT + (reverse ? " DESC" : " ASC");
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;

    String projection = MmsSmsColumns.NORMALIZED_DATE_SENT + ", " + MmsSmsColumns.ADDRESS;
    try (final Cursor cursor = queryTables(projection, selection, true, null, order, null)) {
      String  serializedAddress = address.toString();
      boolean isOwnNumber       = serializedAddress.equals(loginStateRepository.getLocalNumber());

      while (cursor != null && cursor.moveToNext()) {
        boolean timestampMatches = cursor.getLong(0) == sentTimestamp;
        boolean addressMatches   = serializedAddress.equals(cursor.getString(1));

        if (timestampMatches && (addressMatches || isOwnNumber)) {
          return cursor.getPosition();
        }
      }
    }
    return -1;
  }

  // Please note this migration contain a mistake (message_id used as thread_id), it's corrected in the subsequent release,
  // so you shouldn't try to fix it here.
  private static void migrateLegacyCommunityAddresses(final SQLiteDatabase db, final String tableName) {
    final String query = "SELECT " + ID + ", " + MmsSmsColumns.ADDRESS + " FROM " + tableName;
    try (final Cursor cursor = db.rawQuery(query)) {
      while (cursor.moveToNext()) {
        final long threadId = cursor.getLong(0);
        final String address = cursor.getString(1);
        final String newAddress;

        try {
          if (address.startsWith(GroupUtil.COMMUNITY_PREFIX)) {
            // Fill out the real community address from the database
            final String communityQuery = "SELECT public_chat ->>'$.server', public_chat ->> '$.room' FROM loki_public_chat_database WHERE thread_id = ?";

            try (final Cursor communityCursor = db.rawQuery(communityQuery, threadId)) {
              if (communityCursor.moveToNext()) {
                newAddress = new Address.Community(
                        communityCursor.getString(0),
                        communityCursor.getString(1)
                ).toString();
              } else {
                Log.d(TAG, "Unable to find open group for " + address);
                continue;
              }
            }
          } else if (address.startsWith(GroupUtil.COMMUNITY_INBOX_PREFIX)) {
            Triple<String, String, AccountId> triple = GroupUtil.getDecodedOpenGroupInboxID(address);
            if (triple == null) {
              Log.w(TAG, "Unable to decode open group inbox address: " + address);
              continue;
            } else {
              newAddress = new Address.CommunityBlindedId(
                      triple.getFirst(),
                      new Address.Blinded(triple.getThird())
              ).toString();
            }
          } else {
            continue;
          }
        } catch (Throwable e) {
          Log.e(TAG, "Error while migrating address " + address, e);
          continue;
        }

        if (!newAddress.equals(address)) {
          Log.i(TAG, "Migrating thread ID=" + threadId);
          ContentValues contentValues = new ContentValues(1);
          contentValues.put(MmsSmsColumns.ADDRESS, newAddress);
          db.update(tableName, contentValues, ID + " = ?", new String[]{String.valueOf(threadId)});
        }
      }
    }
  }

  // This is an attempt to fix the issue in migrateLegacyCommunityAddresses
  private static void migrateLegacyCommunityAddresses2(final SQLiteDatabase db, final String tableName) {
    final String query = "SELECT " + ID + ", " + THREAD_ID + ", " + MmsSmsColumns.ADDRESS + " FROM " + tableName;
    try (final Cursor cursor = db.rawQuery(query)) {
      while (cursor.moveToNext()) {
        final long messageId = cursor.getLong(0);
        final long threadId = cursor.getLong(1);
        final String address = cursor.getString(2);
        final String newAddress;

        try {
          if (address.startsWith(GroupUtil.COMMUNITY_PREFIX)) {
            // First, if a message has a sender being a community address, it suggests the message
            // is sent by us (this is an assumption from other part of the code).
            // This also means that the address will be the thread's address, if the thread address
            // is indeed a community address
            final String threadSql = "SELECT " + ThreadDatabase.ADDRESS + " FROM " +
                    ThreadDatabase.TABLE_NAME + " WHERE " + ThreadDatabase.ID + " = ?";
            try (final Cursor threadCursor = db.rawQuery(threadSql, threadId)) {
              if (threadCursor.moveToNext()) {
                final Address threadAddress = Address.fromSerialized(threadCursor.getString(0));
                if (threadAddress instanceof Address.Community) {
                  newAddress = threadAddress.getAddress();
                } else {
                  // If this message has a sender being a community address, but the thread address
                  // is not community(!), we'll have to fall back to unsafe group id migration
                  final String groupId = GroupUtil.getDecodedGroupID(address);
                  final int dotIndex = groupId.lastIndexOf('.');
                  if (dotIndex > 0 && dotIndex < groupId.length() - 1) {
                    newAddress = new Address.Community(
                            groupId.substring(0, dotIndex),
                            groupId.substring(dotIndex + 1)
                    ).getAddress();
                  } else {
                    Log.w(TAG, "Unable to decode group id from address: " + address);
                    continue;
                  }
                }
              } else {
                Log.w(TAG, "Thread not found for message id = " + messageId);
                // Thread not found? - this is strange but if we don't have threads these messages
                // aren't visible anyway.
                continue;
              }
            }
          } else {
            continue;
          }
        } catch (Throwable e) {
          Log.e(TAG, "Error while migrating address " + address, e);
          continue;
        }

        if (!newAddress.equals(address)) {
          Log.i(TAG, "Migrating message ID=" + messageId);
          ContentValues contentValues = new ContentValues(1);
          contentValues.put(MmsSmsColumns.ADDRESS, newAddress);
          db.update(tableName, contentValues, ID + " = ?", new String[]{String.valueOf(messageId)});
        }
      }
    }

  }

  public static void migrateLegacyCommunityAddresses(final SQLiteDatabase db) {
    migrateLegacyCommunityAddresses(db, SmsDatabase.TABLE_NAME);
    migrateLegacyCommunityAddresses(db, MmsDatabase.TABLE_NAME);
  }

  public static void migrateLegacyCommunityAddresses2(final SQLiteDatabase db) {
    migrateLegacyCommunityAddresses2(db, SmsDatabase.TABLE_NAME);
    migrateLegacyCommunityAddresses2(db, MmsDatabase.TABLE_NAME);
  }

  private Cursor queryTables(
          @NonNull String projection,
          @Nullable String selection,
          boolean includeReactions,
          @Nullable String additionalReactionSelection,
          @Nullable String order,
          @Nullable String limit) {
    SQLiteDatabase db = getReadableDatabase();
    String query = MmsSmsDatabaseSQLKt.buildMmsSmsCombinedQuery(projection,
            selection,
            includeReactions,
            additionalReactionSelection,
            order,
            limit);
      return db.rawQuery(query, null);
  }

  public Reader readerFor(@NonNull Cursor cursor) {
    return readerFor(cursor, true);
  }

  public Reader readerFor(@NonNull Cursor cursor, boolean getQuote) {
    return new Reader(cursor, getQuote);
  }

  @NonNull
  public Pair<Boolean, Long> timestampAndDirectionForCurrent(@NonNull Cursor cursor) {
    int sentColumn = cursor.getColumnIndex(MmsSmsColumns.NORMALIZED_DATE_SENT);
    String msgType = cursor.getString(cursor.getColumnIndexOrThrow(TRANSPORT));
    long sentTime = cursor.getLong(sentColumn);
    long type = 0;
    if (MmsSmsDatabase.MMS_TRANSPORT.equals(msgType)) {
      int typeIndex = cursor.getColumnIndex(MESSAGE_BOX);
      type = cursor.getLong(typeIndex);
    } else if (MmsSmsDatabase.SMS_TRANSPORT.equals(msgType)) {
      int typeIndex = cursor.getColumnIndex(SmsDatabase.TYPE);
      type = cursor.getLong(typeIndex);
    }

    return new Pair<Boolean, Long>(MmsSmsColumns.Types.isOutgoingMessageType(type), sentTime);
  }

  public class Reader implements Closeable {

    private final Cursor                 cursor;
    private final boolean                getQuote;
    private       SmsDatabase.Reader     smsReader;
    private       MmsDatabase.Reader     mmsReader;

    public Reader(Cursor cursor, boolean getQuote) {
      this.cursor = cursor;
      this.getQuote = getQuote;
    }

    private SmsDatabase.Reader getSmsReader() {
      if (smsReader == null) {
        smsReader = smsDatabase.get().readerFor(cursor);
      }

      return smsReader;
    }

    private MmsDatabase.Reader getMmsReader() {
      if (mmsReader == null) {
        mmsReader = mmsDatabase.get().readerFor(cursor, getQuote);
      }

      return mmsReader;
    }

    public MessageRecord getNext() {
      if (cursor == null || !cursor.moveToNext())
        return null;

      return getCurrent();
    }

    public MessageRecord getCurrent() {
      String type = cursor.getString(cursor.getColumnIndexOrThrow(TRANSPORT));

      if      (MmsSmsDatabase.MMS_TRANSPORT.equals(type)) return getMmsReader().getCurrent();
      else if (MmsSmsDatabase.SMS_TRANSPORT.equals(type)) return getSmsReader().getCurrent();
      else                                                throw new AssertionError("Bad type: " + type);
    }

    public void close() {
      if (cursor != null) {
        cursor.close();
      }
    }
  }
}
