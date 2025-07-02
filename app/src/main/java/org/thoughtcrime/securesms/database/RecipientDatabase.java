package org.thoughtcrime.securesms.database;

import static org.session.libsession.utilities.GroupUtil.COMMUNITY_PREFIX;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LruCache;

import com.annimon.stream.Stream;

import net.zetetic.database.sqlcipher.SQLiteDatabase;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.recipients.RecipientSettings;
import org.session.libsignal.utilities.Base64;
import org.session.libsignal.utilities.Log;
import org.session.libsignal.utilities.guava.Optional;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.inject.Provider;

import kotlinx.coroutines.channels.BufferOverflow;
import kotlinx.coroutines.flow.MutableSharedFlow;
import kotlinx.coroutines.flow.SharedFlow;
import kotlinx.coroutines.flow.SharedFlowKt;
import network.loki.messenger.libsession_util.util.UserPic;

/**
 * The database(table) where some recipient data is stored, including names, avatars, notification settings, etc.
 *
 * Note: We have moved a large chunk of recipient data into the config system, so most of the time you
 * should really get them from {@link org.session.libsession.utilities.ConfigFactoryProtocol} instead.
 *
 * This database is only used for data that is not in the config system, such as blinded contacts,
 * the people who are not your contacts or not your blinded convo, such as unknown people in groups,
 * communities, etc. Their data will be stored here instead.
 */
public class RecipientDatabase extends Database {

  private static final String TAG = RecipientDatabase.class.getSimpleName();

          static final String TABLE_NAME               = "recipient_preferences";
          static final String ID                       = "_id";
  public  static final String ADDRESS                  = "recipient_ids";
          static final String BLOCK                    = "block";
          static final String APPROVED                 = "approved";
  private static final String APPROVED_ME              = "approved_me";
  @Deprecated(forRemoval = true)
  private static final String NOTIFICATION             = "notification";
  @Deprecated(forRemoval = true)
  private static final String VIBRATE                  = "vibrate";
  private static final String MUTE_UNTIL               = "mute_until";
  @Deprecated(forRemoval = true)
  private static final String COLOR                    = "color";
  private static final String SEEN_INVITE_REMINDER     = "seen_invite_reminder";
  @Deprecated(forRemoval = true)
  private static final String DEFAULT_SUBSCRIPTION_ID  = "default_subscription_id";
          static final String EXPIRE_MESSAGES          = "expire_messages";
  @Deprecated(forRemoval = true)
          private static final String DISAPPEARING_STATE       = "disappearing_state";
  @Deprecated(forRemoval = true)
  private static final String REGISTERED               = "registered";
  private static final String PROFILE_KEY              = "profile_key";
  private static final String SYSTEM_DISPLAY_NAME      = "system_display_name";
  @Deprecated(forRemoval = true)
  private static final String SYSTEM_PHOTO_URI         = "system_contact_photo";
  @Deprecated(forRemoval = true)
  private static final String SYSTEM_PHONE_LABEL       = "system_phone_label";
  @Deprecated(forRemoval = true)
  private static final String SYSTEM_CONTACT_URI       = "system_contact_uri";
  private static final String SIGNAL_PROFILE_NAME      = "signal_profile_name";
  private static final String SESSION_PROFILE_AVATAR = "signal_profile_avatar";
  @Deprecated(forRemoval = true)
  private static final String PROFILE_SHARING          = "profile_sharing_approval";
  @Deprecated(forRemoval = true)
  private static final String CALL_RINGTONE            = "call_ringtone";
  @Deprecated(forRemoval = true)
  private static final String CALL_VIBRATE             = "call_vibrate";
  @Deprecated(forRemoval = true)
  private static final String NOTIFICATION_CHANNEL     = "notification_channel";
  @Deprecated(forRemoval = true)
  private static final String UNIDENTIFIED_ACCESS_MODE = "unidentified_access_mode";
  @Deprecated(forRemoval = true)
  private static final String FORCE_SMS_SELECTION      = "force_sms_selection";
  private static final String NOTIFY_TYPE              = "notify_type"; // all, mentions only, none
  @Deprecated(forRemoval = true)
  private static final String WRAPPER_HASH             = "wrapper_hash";
  private static final String BLOCKS_COMMUNITY_MESSAGE_REQUESTS = "blocks_community_message_requests";
  private static final String AUTO_DOWNLOAD            = "auto_download"; // 1 / 0 / -1 flag for whether to auto-download in a conversation, or if the user hasn't selected a preference

  private static final String[] RECIPIENT_PROJECTION = new String[] {
      BLOCK, APPROVED, APPROVED_ME, NOTIFICATION, CALL_RINGTONE, VIBRATE, CALL_VIBRATE, MUTE_UNTIL, COLOR, SEEN_INVITE_REMINDER, DEFAULT_SUBSCRIPTION_ID, EXPIRE_MESSAGES, REGISTERED,
      PROFILE_KEY, SYSTEM_DISPLAY_NAME, SYSTEM_PHOTO_URI, SYSTEM_PHONE_LABEL, SYSTEM_CONTACT_URI,
      SIGNAL_PROFILE_NAME, SESSION_PROFILE_AVATAR, PROFILE_SHARING, NOTIFICATION_CHANNEL,
      UNIDENTIFIED_ACCESS_MODE,
      FORCE_SMS_SELECTION, NOTIFY_TYPE, DISAPPEARING_STATE, WRAPPER_HASH, BLOCKS_COMMUNITY_MESSAGE_REQUESTS, AUTO_DOWNLOAD,
  };

  static final List<String> TYPED_RECIPIENT_PROJECTION = Stream.of(RECIPIENT_PROJECTION)
                                                               .map(columnName -> TABLE_NAME + "." + columnName)
                                                               .toList();

  public static final String CREATE_TABLE =
      "CREATE TABLE " + TABLE_NAME +
          " (" + ID + " INTEGER PRIMARY KEY, " +
          ADDRESS + " TEXT UNIQUE, " +
          BLOCK + " INTEGER DEFAULT 0," +
          NOTIFICATION + " TEXT DEFAULT NULL, " +
          VIBRATE + " INTEGER DEFAULT 0, " +
          MUTE_UNTIL + " INTEGER DEFAULT 0, " +
          COLOR + " TEXT DEFAULT NULL, " +
          SEEN_INVITE_REMINDER + " INTEGER DEFAULT 0, " +
          DEFAULT_SUBSCRIPTION_ID + " INTEGER DEFAULT -1, " +
          EXPIRE_MESSAGES + " INTEGER DEFAULT 0, " +
          REGISTERED + " INTEGER DEFAULT 0, " +
          SYSTEM_DISPLAY_NAME + " TEXT DEFAULT NULL, " +
          SYSTEM_PHOTO_URI + " TEXT DEFAULT NULL, " +
          SYSTEM_PHONE_LABEL + " TEXT DEFAULT NULL, " +
          SYSTEM_CONTACT_URI + " TEXT DEFAULT NULL, " +
          PROFILE_KEY + " TEXT DEFAULT NULL, " +
          SIGNAL_PROFILE_NAME + " TEXT DEFAULT NULL, " +
          SESSION_PROFILE_AVATAR + " TEXT DEFAULT NULL, " +
          PROFILE_SHARING + " INTEGER DEFAULT 0, " +
          CALL_RINGTONE + " TEXT DEFAULT NULL, " +
          CALL_VIBRATE + " INTEGER DEFAULT 0, " +
          NOTIFICATION_CHANNEL + " TEXT DEFAULT NULL, " +
          UNIDENTIFIED_ACCESS_MODE + " INTEGER DEFAULT 0, " +
          FORCE_SMS_SELECTION + " INTEGER DEFAULT 0);";

  public static String getCreateNotificationTypeCommand() {
    return "ALTER TABLE "+ TABLE_NAME + " " +
            "ADD COLUMN " + NOTIFY_TYPE + " INTEGER DEFAULT 0;";
  }

  public static String getCreateAutoDownloadCommand() {
    return "ALTER TABLE "+ TABLE_NAME + " " +
          "ADD COLUMN " + AUTO_DOWNLOAD + " INTEGER DEFAULT -1;";
  }

  public static String getUpdateAutoDownloadValuesCommand() {
    return "UPDATE "+TABLE_NAME+" SET "+AUTO_DOWNLOAD+" = 1 "+
            "WHERE "+ADDRESS+" IN (SELECT "+SessionContactDatabase.sessionContactTable+"."+SessionContactDatabase.accountID+" "+
            "FROM "+SessionContactDatabase.sessionContactTable+" WHERE ("+SessionContactDatabase.isTrusted+" != 0))";
  }

  public static String getCreateApprovedCommand() {
    return "ALTER TABLE "+ TABLE_NAME + " " +
            "ADD COLUMN " + APPROVED + " INTEGER DEFAULT 0;";
  }

  public static String getCreateApprovedMeCommand() {
    return "ALTER TABLE "+ TABLE_NAME + " " +
            "ADD COLUMN " + APPROVED_ME + " INTEGER DEFAULT 0;";
  }

  public static String getUpdateApprovedCommand() {
    return "UPDATE "+ TABLE_NAME + " " +
            "SET " + APPROVED + " = 1, " + APPROVED_ME + " = 1 " +
            "WHERE " + ADDRESS + " NOT LIKE '" + COMMUNITY_PREFIX + "%'";
  }

  public static String getUpdateResetApprovedCommand() {
    return "UPDATE "+ TABLE_NAME + " " +
            "SET " + APPROVED + " = 0, " + APPROVED_ME + " = 0 " +
            "WHERE " + ADDRESS + " NOT LIKE '" + COMMUNITY_PREFIX + "%'";
  }

  public static String getUpdateApprovedSelectConversations() {
    return "UPDATE "+ TABLE_NAME + " SET "+APPROVED+" = 1, "+APPROVED_ME+" = 1 "+
            "WHERE "+ADDRESS+ " NOT LIKE '"+ COMMUNITY_PREFIX +"%' " +
            "AND ("+ADDRESS+" IN (SELECT "+ThreadDatabase.TABLE_NAME+"."+ThreadDatabase.ADDRESS+" FROM "+ThreadDatabase.TABLE_NAME+" WHERE "+
            ADDRESS +" IN (SELECT "+GroupDatabase.TABLE_NAME+"."+GroupDatabase.ADMINS+" FROM "+GroupDatabase.TABLE_NAME+")))";
  }

  public static String getCreateDisappearingStateCommand() {
    return "ALTER TABLE "+ TABLE_NAME + " " +
            "ADD COLUMN " + DISAPPEARING_STATE + " INTEGER DEFAULT 0;";
  }

  public static String getAddWrapperHash() {
    return "ALTER TABLE "+TABLE_NAME+" "+
            "ADD COLUMN "+WRAPPER_HASH+" TEXT DEFAULT NULL;";
  }

  public static String getAddBlocksCommunityMessageRequests() {
    return "ALTER TABLE "+TABLE_NAME+" "+
            "ADD COLUMN "+BLOCKS_COMMUNITY_MESSAGE_REQUESTS+" INT DEFAULT 0;";
  }

  public static final int NOTIFY_TYPE_ALL = 0;
  public static final int NOTIFY_TYPE_MENTIONS = 1;
  public static final int NOTIFY_TYPE_NONE = 2;

  @NonNull
  private final MutableSharedFlow<Address> updateNotifications = SharedFlowKt.MutableSharedFlow(0, 256, BufferOverflow.DROP_OLDEST);

  @NonNull
  private final LruCache<Address, RecipientSettings> recipientSettingsCache = new LruCache<>(512);

  public RecipientDatabase(Context context, Provider<SQLCipherOpenHelper> databaseHelper) {
    super(context, databaseHelper);
  }

  @NonNull
  public SharedFlow<Address> getUpdateNotifications() {
    return updateNotifications;
  }

  @Nullable
  public RecipientSettings getRecipientSettings(@NonNull Address address) {
    final RecipientSettings cachedSettings = recipientSettingsCache.get(address);
    if (cachedSettings != null) {
      return cachedSettings;
    }

    SQLiteDatabase database = getReadableDatabase();

    try (Cursor cursor = database.query(TABLE_NAME, null, ADDRESS + " = ?", new String[]{address.toString()}, null, null, null)) {

      if (cursor != null && cursor.moveToNext()) {
        RecipientSettings settings = getRecipientSettings(cursor);
        recipientSettingsCache.put(address, settings);
        return settings;
      }

      return null;
    }
  }

  @NonNull
  private RecipientSettings getRecipientSettings(@NonNull Cursor cursor) {
    boolean blocked                 = cursor.getInt(cursor.getColumnIndexOrThrow(BLOCK))                == 1;
    boolean approved                = cursor.getInt(cursor.getColumnIndexOrThrow(APPROVED))             == 1;
    boolean approvedMe              = cursor.getInt(cursor.getColumnIndexOrThrow(APPROVED_ME))          == 1;
    long    muteUntil               = cursor.getLong(cursor.getColumnIndexOrThrow(MUTE_UNTIL));
    int     notifyType              = cursor.getInt(cursor.getColumnIndexOrThrow(NOTIFY_TYPE));
    Boolean autoDownloadAttachments = switch (cursor.getInt(cursor.getColumnIndexOrThrow(AUTO_DOWNLOAD))) {
        case 1 -> true;
        case -1 -> null;
        default -> false;
    };

    int     expireMessages          = cursor.getInt(cursor.getColumnIndexOrThrow(EXPIRE_MESSAGES));
    String  profileKeyString        = cursor.getString(cursor.getColumnIndexOrThrow(PROFILE_KEY));
    String  systemDisplayName       = cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_DISPLAY_NAME));
    String  signalProfileName       = cursor.getString(cursor.getColumnIndexOrThrow(SIGNAL_PROFILE_NAME));
    String  signalProfileAvatar     = cursor.getString(cursor.getColumnIndexOrThrow(SESSION_PROFILE_AVATAR));
    boolean blocksCommunityMessageRequests = cursor.getInt(cursor.getColumnIndexOrThrow(BLOCKS_COMMUNITY_MESSAGE_REQUESTS)) == 1;

    byte[] profileKey = null;

    if (profileKeyString != null) {
      try {
        profileKey = Base64.decode(profileKeyString);
      } catch (IOException e) {
        Log.w(TAG, e);
        profileKey = null;
      }
    }

    return new RecipientSettings(blocked, approved, approvedMe, muteUntil,
            notifyType, autoDownloadAttachments,
            expireMessages,
            profileKey, systemDisplayName,
            signalProfileName, signalProfileAvatar,
            blocksCommunityMessageRequests);
  }

  private void invalidateCache(@NonNull Address recipient) {
    recipientSettingsCache.remove(recipient);
  }

  public void setApproved(@NonNull Address recipient, boolean approved) {
    ContentValues values = new ContentValues();
    values.put(APPROVED, approved ? 1 : 0);
    updateOrInsert(recipient, values);
    notifyRecipientListeners();

    invalidateCache(recipient);
    updateNotifications.tryEmit(recipient);
  }

  public void setApprovedMe(@NonNull Address recipient, boolean approvedMe) {
    ContentValues values = new ContentValues();
    values.put(APPROVED_ME, approvedMe ? 1 : 0);
    updateOrInsert(recipient, values);
    notifyRecipientListeners();

    invalidateCache(recipient);
    updateNotifications.tryEmit(recipient);
  }

  public void setBlocked(@NonNull Iterable<Address> recipients, boolean blocked) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues values = new ContentValues();
      values.put(BLOCK, blocked ? 1 : 0);
      for (Address recipient : recipients) {
        db.update(TABLE_NAME, values, ADDRESS + " = ?", new String[]{recipient.toString()});
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }

    for (Address recipient : recipients) {
        invalidateCache(recipient);
        updateNotifications.tryEmit(recipient);
    }

    notifyRecipientListeners();
  }

  // Delete a recipient with the given address from the database
  public void deleteRecipient(@NonNull String recipientAddress) {
    Address address = Address.fromSerialized(recipientAddress);
    SQLiteDatabase db = getWritableDatabase();
    int rowCount = db.delete(TABLE_NAME, ADDRESS + " = ?", new String[] { recipientAddress });
    if (rowCount == 0) { Log.w(TAG, "Could not find to delete recipient with address: " + recipientAddress); }

    invalidateCache(address);
    notifyRecipientListeners();
    updateNotifications.tryEmit(address);
  }

  public void setAutoDownloadAttachments(@NonNull Address recipient, boolean shouldAutoDownloadAttachments) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues values = new ContentValues();
      values.put(AUTO_DOWNLOAD, shouldAutoDownloadAttachments ? 1 : 0);
      db.update(TABLE_NAME, values, ADDRESS+ " = ?", new String[]{recipient.toString()});
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }

    invalidateCache(recipient);
    notifyRecipientListeners();
    updateNotifications.tryEmit(recipient);
  }

  public void setMuted(@NonNull Address recipient, long until) {
    ContentValues values = new ContentValues();
    values.put(MUTE_UNTIL, until);
    updateOrInsert(recipient, values);
    notifyRecipientListeners();
    updateNotifications.tryEmit(recipient);
  }

  /**
   *
   * @param recipient to modify notifications for
   * @param notifyType the new notification type {@link #NOTIFY_TYPE_ALL}, {@link #NOTIFY_TYPE_MENTIONS} or {@link #NOTIFY_TYPE_NONE}
   */
  public void setNotifyType(@NonNull Address recipient, int notifyType) {
    ContentValues values = new ContentValues();
    values.put(NOTIFY_TYPE, notifyType);
    updateOrInsert(recipient, values);

    invalidateCache(recipient);
    notifyConversationListListeners();
    notifyRecipientListeners();
    updateNotifications.tryEmit(recipient);
  }

  public void updateProfile(@NonNull Address recipient,
                            @Nullable String newName,
                            @Nullable UserPic profilePic,
                            @Nullable Boolean acceptsCommunityRequests) {
    if (newName == null && profilePic == null) {
      return; // nothing to update
    }

    // This call could be called with a lot of same data so it's worth checking if we
    // actually need to update the database.
    final RecipientSettings cached = recipientSettingsCache.get(recipient);
    if (cached != null &&
            Objects.equals(cached.getSystemDisplayName(), newName) &&
            Objects.equals(cached.getProfilePic(), profilePic) &&
            (acceptsCommunityRequests == null || !cached.getBlocksCommunityMessagesRequests() == acceptsCommunityRequests)
    ) {
        Log.w(TAG, "No changes to update for recipient: " + recipient);
        return;
    }

    ContentValues contentValues = new ContentValues(4);
    if (newName != null) {
      contentValues.put(SYSTEM_DISPLAY_NAME, newName);
    }
    if (profilePic != null) {
      contentValues.put(SESSION_PROFILE_AVATAR, profilePic.getUrl());
      contentValues.put(PROFILE_KEY, Base64.encodeBytes(profilePic.getKeyAsByteArray()));
    }

    if (acceptsCommunityRequests != null) {
      contentValues.put(BLOCKS_COMMUNITY_MESSAGE_REQUESTS, acceptsCommunityRequests ? 0 : 1);
    }

    updateOrInsert(recipient, contentValues);
    invalidateCache(recipient);
    updateNotifications.tryEmit(recipient);
  }

  public void setNotificationChannel(@NonNull Address recipient, @Nullable String notificationChannel) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(NOTIFICATION_CHANNEL, notificationChannel);
    updateOrInsert(recipient, contentValues);
    invalidateCache(recipient);
    notifyRecipientListeners();
    updateNotifications.tryEmit(recipient);
  }

  public void setBlocksCommunityMessageRequests(@NonNull Address recipient, boolean isBlocked) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(BLOCKS_COMMUNITY_MESSAGE_REQUESTS, isBlocked ? 1 : 0);
    updateOrInsert(recipient, contentValues);
    invalidateCache(recipient);
    notifyRecipientListeners();
    updateNotifications.tryEmit(recipient);
  }

  private void updateOrInsert(Address address, ContentValues contentValues) {
    SQLiteDatabase database = getWritableDatabase();

    database.beginTransaction();

    int updated = database.update(TABLE_NAME, contentValues, ADDRESS + " = ?",
                                  new String[] {address.toString()});

    if (updated < 1) {
      contentValues.put(ADDRESS, address.toString());
      database.insert(TABLE_NAME, null, contentValues);
    }

    database.setTransactionSuccessful();
    database.endTransaction();
  }

  public List<Address> getBlockedContacts() {
    SQLiteDatabase database = getReadableDatabase();

    try (Cursor         cursor   = database.query(TABLE_NAME, new String[] {ID, ADDRESS}, BLOCK + " = 1",
            null, null, null, null, null)) {
      List<Address> blockedContacts = new ArrayList<>(cursor.getCount());
      while (cursor.moveToNext()) {
          String serialized = cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS));
          blockedContacts.add(Address.fromSerialized(serialized));
      }
      return blockedContacts;
    }
  }

  /**
   * Returns a list of all recipients in the database.
   *
   * @return A list of all recipients
   */
  public List<Address> getAllRecipients() {
    SQLiteDatabase database = getReadableDatabase();

    try(final Cursor cursor = database.query(TABLE_NAME, new String[] {ADDRESS}, null,
            null, null, null, null, null)) {
      final List<Address> recipients = new ArrayList<>(cursor.getCount());
      while (cursor.moveToNext()) {
          String serialized = cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS));
          recipients.add(Address.fromSerialized(serialized));
      }
      return recipients;
    }
  }
}
