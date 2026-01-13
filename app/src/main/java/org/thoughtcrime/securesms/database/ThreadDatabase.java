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

import static org.thoughtcrime.securesms.database.GroupDatabase.TYPED_GROUP_PROJECTION;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.collection.ArrayMap;

import com.annimon.stream.Stream;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.json.JSONArray;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.ConfigFactoryProtocol;
import org.session.libsession.utilities.GroupUtil;
import org.session.libsignal.utilities.AccountId;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.database.model.ThreadRecord;
import org.thoughtcrime.securesms.database.model.content.MessageContent;
import org.thoughtcrime.securesms.notifications.MarkReadProcessor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import dagger.Lazy;
import kotlin.Triple;
import kotlin.collections.CollectionsKt;
import kotlinx.coroutines.channels.BufferOverflow;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.MutableSharedFlow;
import kotlinx.coroutines.flow.SharedFlowKt;

@Singleton
public class ThreadDatabase extends Database {


  private static final String TAG = ThreadDatabase.class.getSimpleName();

  // Map of threadID -> Address

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
  @Deprecated(forRemoval = true)
  public  static final String DISTRIBUTION_TYPE      = "type"; // See: DistributionTypes.kt
  private static final String ERROR                  = "error";
  public  static final String SNIPPET_TYPE           = "snippet_type";
  @Deprecated(forRemoval = true)
  public  static final String SNIPPET_URI            = "snippet_uri";
  /**
   * The column that hold a {@link MessageContent}. See {@link MmsDatabase#MESSAGE_CONTENT} for more information
   */
  public  static final String SNIPPET_CONTENT        = "snippet_content";
  public  static final String ARCHIVED               = "archived";
  public  static final String STATUS                 = "status";
  public  static final String DELIVERY_RECEIPT_COUNT = "delivery_receipt_count";
  public  static final String READ_RECEIPT_COUNT     = "read_receipt_count";
  @Deprecated(forRemoval = true)
  public  static final String EXPIRES_IN             = "expires_in";
  public  static final String LAST_SEEN              = "last_seen";
  public static final String HAS_SENT                = "has_sent";

  @Deprecated(forRemoval = true)
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

  public static final String[] CREATE_ADDRESS_INDEX = {
     // First remove duplicated addresses if any - this should not be the case as there's application level protection in place but just to make sure
     "DELETE FROM " + TABLE_NAME + " WHERE " + ID + " NOT IN (SELECT " + ID + " FROM " + TABLE_NAME + " GROUP BY " + ADDRESS + ")",
     // Then create an index on the address column
     "CREATE UNIQUE INDEX thread_addresses ON " + TABLE_NAME + " (" + ADDRESS + ");"
  };

  private static final String[] THREAD_PROJECTION = {
      ID, THREAD_CREATION_DATE, MESSAGE_COUNT, ADDRESS, SNIPPET, SNIPPET_CHARSET, READ, UNREAD_COUNT, UNREAD_MENTION_COUNT, DISTRIBUTION_TYPE, ERROR, SNIPPET_TYPE,
      SNIPPET_URI, ARCHIVED, STATUS, DELIVERY_RECEIPT_COUNT, EXPIRES_IN, LAST_SEEN, READ_RECEIPT_COUNT, IS_PINNED, SNIPPET_CONTENT,
  };

  private static final List<String> TYPED_THREAD_PROJECTION = Stream.of(THREAD_PROJECTION)
                                                                    .map(columnName -> TABLE_NAME + "." + columnName)
                                                                    .toList();

  private static final List<String> COMBINED_THREAD_RECIPIENT_GROUP_PROJECTION =
          CollectionsKt.plus(
          CollectionsKt.plus(
                  TYPED_THREAD_PROJECTION,
                  TYPED_GROUP_PROJECTION
          ), LokiMessageDatabase.groupInviteTable+"."+LokiMessageDatabase.invitingSessionId);


  public static String getCreatePinnedCommand() {
    return "ALTER TABLE "+ TABLE_NAME + " " +
            "ADD COLUMN " + IS_PINNED + " INTEGER DEFAULT 0;";
  }

  public static String getUnreadMentionCountCommand() {
    return "ALTER TABLE "+ TABLE_NAME + " " +
            "ADD COLUMN " + UNREAD_MENTION_COUNT + " INTEGER DEFAULT 0;";
  }

  public static void migrateLegacyCommunityAddresses(final SQLiteDatabase db) {
    final String query = "SELECT " + ID + ", " + ADDRESS + " FROM " + TABLE_NAME;
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
                contentValues.put(ADDRESS, newAddress);
                db.update(TABLE_NAME, contentValues, ID + " = ?", new String[]{String.valueOf(threadId)});
            }
        }
    }
  }


  private final MutableSharedFlow<Long> updateNotifications = SharedFlowKt.MutableSharedFlow(0, 256, BufferOverflow.DROP_OLDEST);

  final Lazy<@NonNull RecipientRepository> recipientRepository;
  final Lazy<@NonNull MmsSmsDatabase> mmsSmsDatabase;
  final Lazy<@NonNull ConfigFactoryProtocol> configFactory;
  private final Lazy<@NonNull MessageNotifier> messageNotifier;
  private final Lazy<@NonNull MmsDatabase> mmsDatabase;
  private final Lazy<@NonNull SmsDatabase> smsDatabase;
  private final Lazy<@NonNull MarkReadProcessor> markReadProcessor;

  @Inject
  public ThreadDatabase(@dagger.hilt.android.qualifiers.ApplicationContext Context context,
                        Provider<SQLCipherOpenHelper> databaseHelper,
                        Lazy<@NonNull RecipientRepository> recipientRepository,
                        Lazy<@NonNull MmsSmsDatabase> mmsSmsDatabase,
                        Lazy<@NonNull ConfigFactoryProtocol> configFactory,
                        Lazy<@NonNull MessageNotifier> messageNotifier,
                        Lazy<@NonNull MmsDatabase> mmsDatabase,
                        Lazy<@NonNull SmsDatabase> smsDatabase,
                        Lazy<@NonNull MarkReadProcessor> markReadProcessor) {
    super(context, databaseHelper);
    this.recipientRepository = recipientRepository;
    this.mmsSmsDatabase = mmsSmsDatabase;
    this.configFactory = configFactory;
    this.messageNotifier = messageNotifier;
    this.mmsDatabase = mmsDatabase;
    this.smsDatabase = smsDatabase;
    this.markReadProcessor = markReadProcessor;
  }

  @NonNull
  public Flow<Long> getUpdateNotifications() {
    return updateNotifications;
  }


  public void deleteThread(long threadId) {
    SQLiteDatabase db = getWritableDatabase();
    if (db.delete(TABLE_NAME, ID_WHERE, new String[] {threadId + ""}) > 0) {
      notifyThreadUpdated(threadId);
    }
  }

  public static class EnsureThreadsResult {
    @NonNull
    public final Map<Address, Long> deletedThreads;

    @NonNull
    public final Map<Address, Long> createdThreads;

    public EnsureThreadsResult(@NonNull Map<Address, Long> deletedThreads, @NonNull Map<Address, Long> createdThreads) {
        this.deletedThreads = deletedThreads;
        this.createdThreads = createdThreads;
    }
  }

  /**
   * This method ensures that the threads for the given addresses exist in the database, AND
   * deletes any threads that are not in the given addresses.
   *
   * @return The list of thread IDs that were deleted.
   */
  @NonNull
  public EnsureThreadsResult ensureThreads(@NonNull final Iterable<Address.Conversable> addresses) {
    final SQLiteDatabase db = getWritableDatabase();

    db.beginTransaction();

    final Map<Address, Long> deletedThreads, createdThreads;

    try {
      // First delete threads that are not in the given addresses
      final String deletionSql = "DELETE FROM " + TABLE_NAME + " " +
              "WHERE " + ADDRESS + " NOT IN (SELECT value FROM json_each(?)) " +
              "RETURNING " + ID + ", " + ADDRESS;
      final String addressListAsJson = new JSONArray(CollectionsKt.map(addresses, Address::getAddress)).toString();

      try (final Cursor cursor = db.rawQuery(deletionSql, addressListAsJson)) {
        deletedThreads = new ArrayMap<>(cursor.getCount());
        while (cursor.moveToNext()) {
          deletedThreads.put(
              Address.fromSerialized(cursor.getString(1)),
              cursor.getLong(0)
          );
        }
      }

      // Second, ensure that threads for the given addresses exist
      final String insertionSql = "INSERT OR IGNORE INTO " + TABLE_NAME + " (" + ADDRESS + ") " +
              "SELECT value FROM json_each(?) " +
              "RETURNING " + ID + ", " + ADDRESS;

      try (final Cursor cursor = db.rawQuery(insertionSql, addressListAsJson)) {
        createdThreads = new ArrayMap<>(cursor.getCount());
        while (cursor.moveToNext()) {
          createdThreads.put(
              Address.fromSerialized(cursor.getString(1)),
              cursor.getLong(0)
          );
        }
      }

      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }

    // Notify that the threads were deleted
    for (final Long deletedThread : deletedThreads.values()) {
      notifyThreadUpdated(deletedThread);
    }

    // Notify that the threads were created
    for (final Long createdThread : createdThreads.values()) {
      notifyThreadUpdated(createdThread);
    }

    return new EnsureThreadsResult(deletedThreads, createdThreads);
  }

  public void trimThreadBefore(long threadId, long timestamp) {
    Log.i("ThreadDatabase", "Trimming thread: " + threadId + " before :"+timestamp);
    smsDatabase.get().deleteMessagesInThreadBeforeDate(threadId, timestamp);
    mmsDatabase.get().deleteMessagesInThreadBeforeDate(threadId, timestamp, false);
    notifyThreadUpdated(threadId);
  }

  public void setCreationDate(long threadId, long date) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(THREAD_CREATION_DATE, date);
    SQLiteDatabase db = getWritableDatabase();
    int updated = db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId+""});
    if (updated > 0) notifyThreadUpdated(threadId);
  }

    @NonNull
  public List<ThreadRecord> getThreads(@Nullable Collection<? extends Address.Conversable> addresses) {
    if (addresses == null || addresses.isEmpty())
      return Collections.emptyList();

    return ThreadDatabaseExtKt.queryThreads(this, addresses);
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

  public List<Long> getThreadIDsFor(Collection<? extends Address> addresses) {
    final String where = ADDRESS + " IN (SELECT value FROM json_each(?))";
    final String whereArg = new JSONArray(CollectionsKt.map(addresses, Address::getAddress)).toString();

    try (final Cursor cursor = getReadableDatabase().query(TABLE_NAME, new String[]{ID}, where,
            new String[]{whereArg}, null, null, null)) {
      List<Long> threadIds = new ArrayList<>(cursor.getCount());
      while (cursor.moveToNext()) {
        threadIds.add(cursor.getLong(cursor.getColumnIndexOrThrow(ID)));
      }
      return threadIds;
    }
  }

  public long getThreadIdIfExistsFor(String address) {
    SQLiteDatabase db      = getReadableDatabase();
    String where           = ADDRESS + " = ?";
    String[] recipientsArg = new String[] {address};

    try (final Cursor cursor = db.query(TABLE_NAME, new String[]{ID}, where, recipientsArg, null, null, null)) {
      if (cursor.moveToFirst())
        return cursor.getLong(cursor.getColumnIndexOrThrow(ID));
      else
        return -1L;
    }
  }

  public long getThreadIdIfExistsFor(Address address) {
    return getThreadIdIfExistsFor(address.getAddress());
  }

  public long getOrCreateThreadIdFor(Address address) {
    boolean created = false;

    ContentValues contentValues = new ContentValues(1);
    contentValues.put(ADDRESS, address.toString());
    long threadId = getWritableDatabase().insertWithOnConflict(TABLE_NAME, null, contentValues, SQLiteDatabase.CONFLICT_IGNORE);

    if (threadId < 0) {
      threadId = getThreadIdIfExistsFor(address);
    } else {
      created = true;
    }

    if (created) {
      updateNotifications.tryEmit(threadId);
    }

    return threadId;
  }

  public @Nullable Address getRecipientForThreadId(long threadId) {
    SQLiteDatabase db = getReadableDatabase();

    try (final Cursor cursor = db.query(TABLE_NAME, new String[] { ADDRESS }, ID + " = ?", new String[] { String.valueOf(threadId )}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return Address.fromSerialized(cursor.getString(0));
      }
    }

    return null;
  }

  public void notifyThreadUpdated(long threadId) {
    Log.d(TAG, "Notifying thread updated: " + threadId);
    updateNotifications.tryEmit(threadId);
  }
}
