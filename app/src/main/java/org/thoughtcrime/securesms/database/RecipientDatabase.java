package org.thoughtcrime.securesms.database;

import static org.session.libsession.utilities.GroupUtil.COMMUNITY_PREFIX;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.annimon.stream.Stream;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.MaterialColor;
import org.session.libsession.utilities.Util;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsession.utilities.recipients.Recipient.RecipientSettings;
import org.session.libsession.utilities.recipients.Recipient.RegisteredState;
import org.session.libsignal.utilities.Base64;
import org.session.libsignal.utilities.Log;
import org.session.libsignal.utilities.guava.Optional;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Provider;

public class RecipientDatabase extends Database {

  private static final String TAG = RecipientDatabase.class.getSimpleName();

          static final String TABLE_NAME               = "recipient_preferences";
          static final String ID                       = "_id";
  public  static final String ADDRESS                  = "recipient_ids";
          static final String BLOCK                    = "block";
          static final String APPROVED                 = "approved";
  private static final String APPROVED_ME              = "approved_me";
  private static final String NOTIFICATION             = "notification";
  private static final String VIBRATE                  = "vibrate";
  private static final String MUTE_UNTIL               = "mute_until";
  private static final String COLOR                    = "color";
  private static final String SEEN_INVITE_REMINDER     = "seen_invite_reminder";
  private static final String DEFAULT_SUBSCRIPTION_ID  = "default_subscription_id";
          static final String EXPIRE_MESSAGES          = "expire_messages";
  private static final String DISAPPEARING_STATE       = "disappearing_state";
  private static final String REGISTERED               = "registered";
  private static final String PROFILE_KEY              = "profile_key";
  private static final String SYSTEM_DISPLAY_NAME      = "system_display_name";
  private static final String SYSTEM_PHOTO_URI         = "system_contact_photo";
  private static final String SYSTEM_PHONE_LABEL       = "system_phone_label";
  private static final String SYSTEM_CONTACT_URI       = "system_contact_uri";
  private static final String SIGNAL_PROFILE_NAME      = "signal_profile_name";
  private static final String SESSION_PROFILE_AVATAR = "signal_profile_avatar";
  private static final String PROFILE_SHARING          = "profile_sharing_approval";
  private static final String CALL_RINGTONE            = "call_ringtone";
  private static final String CALL_VIBRATE             = "call_vibrate";
  private static final String NOTIFICATION_CHANNEL     = "notification_channel";
  @Deprecated(forRemoval = true)
  private static final String UNIDENTIFIED_ACCESS_MODE = "unidentified_access_mode";
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
          VIBRATE + " INTEGER DEFAULT " + Recipient.VibrateState.DEFAULT.getId() + ", " +
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
          CALL_VIBRATE + " INTEGER DEFAULT " + Recipient.VibrateState.DEFAULT.getId() + ", " +
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

  public RecipientDatabase(Context context, Provider<SQLCipherOpenHelper> databaseHelper) {
    super(context, databaseHelper);
  }

  public RecipientReader getRecipientsWithNotificationChannels() {
    SQLiteDatabase database = getReadableDatabase();
    Cursor         cursor   = database.query(TABLE_NAME, new String[] {ID, ADDRESS}, NOTIFICATION_CHANNEL  + " NOT NULL",
                                             null, null, null, null, null);

    return new RecipientReader(context, cursor);
  }

  public Optional<RecipientSettings> getRecipientSettings(@NonNull Address address) {
    SQLiteDatabase database = getReadableDatabase();

    try (Cursor cursor = database.query(TABLE_NAME, null, ADDRESS + " = ?", new String[]{address.toString()}, null, null, null)) {

      if (cursor != null && cursor.moveToNext()) {
        return getRecipientSettings(cursor);
      }

      return Optional.absent();
    }
  }

  Optional<RecipientSettings> getRecipientSettings(@NonNull Cursor cursor) {
    boolean blocked                 = cursor.getInt(cursor.getColumnIndexOrThrow(BLOCK))                == 1;
    boolean approved                = cursor.getInt(cursor.getColumnIndexOrThrow(APPROVED))             == 1;
    boolean approvedMe              = cursor.getInt(cursor.getColumnIndexOrThrow(APPROVED_ME))          == 1;
    String  messageRingtone         = cursor.getString(cursor.getColumnIndexOrThrow(NOTIFICATION));
    String  callRingtone            = cursor.getString(cursor.getColumnIndexOrThrow(CALL_RINGTONE));
    int     disappearingState       = cursor.getInt(cursor.getColumnIndexOrThrow(DISAPPEARING_STATE));
    int     messageVibrateState     = cursor.getInt(cursor.getColumnIndexOrThrow(VIBRATE));
    int     callVibrateState        = cursor.getInt(cursor.getColumnIndexOrThrow(CALL_VIBRATE));
    long    muteUntil               = cursor.getLong(cursor.getColumnIndexOrThrow(MUTE_UNTIL));
    int     notifyType              = cursor.getInt(cursor.getColumnIndexOrThrow(NOTIFY_TYPE));
    boolean autoDownloadAttachments = cursor.getInt(cursor.getColumnIndexOrThrow(AUTO_DOWNLOAD))        == 1;
    String  serializedColor         = cursor.getString(cursor.getColumnIndexOrThrow(COLOR));
    int     defaultSubscriptionId   = cursor.getInt(cursor.getColumnIndexOrThrow(DEFAULT_SUBSCRIPTION_ID));
    int     expireMessages          = cursor.getInt(cursor.getColumnIndexOrThrow(EXPIRE_MESSAGES));
    int     registeredState         = cursor.getInt(cursor.getColumnIndexOrThrow(REGISTERED));
    String  profileKeyString        = cursor.getString(cursor.getColumnIndexOrThrow(PROFILE_KEY));
    String  systemDisplayName       = cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_DISPLAY_NAME));
    String  systemContactPhoto      = cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_PHOTO_URI));
    String  systemPhoneLabel        = cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_PHONE_LABEL));
    String  systemContactUri        = cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_CONTACT_URI));
    String  signalProfileName       = cursor.getString(cursor.getColumnIndexOrThrow(SIGNAL_PROFILE_NAME));
    String  signalProfileAvatar     = cursor.getString(cursor.getColumnIndexOrThrow(SESSION_PROFILE_AVATAR));
    boolean profileSharing          = cursor.getInt(cursor.getColumnIndexOrThrow(PROFILE_SHARING))      == 1;
    String  notificationChannel     = cursor.getString(cursor.getColumnIndexOrThrow(NOTIFICATION_CHANNEL));
    boolean forceSmsSelection       = cursor.getInt(cursor.getColumnIndexOrThrow(FORCE_SMS_SELECTION))  == 1;
    boolean blocksCommunityMessageRequests = cursor.getInt(cursor.getColumnIndexOrThrow(BLOCKS_COMMUNITY_MESSAGE_REQUESTS)) == 1;

    MaterialColor color;
    byte[] profileKey = null;

    try {
      color = serializedColor == null ? null : MaterialColor.fromSerialized(serializedColor);
    } catch (MaterialColor.UnknownColorException e) {
      Log.w(TAG, e);
      color = null;
    }

    if (profileKeyString != null) {
      try {
        profileKey = Base64.decode(profileKeyString);
      } catch (IOException e) {
        Log.w(TAG, e);
        profileKey = null;
      }
    }

    return Optional.of(new RecipientSettings(blocked, approved, approvedMe, muteUntil,
                                             notifyType, autoDownloadAttachments,
                                             Recipient.DisappearingState.fromId(disappearingState),
                                             Recipient.VibrateState.fromId(messageVibrateState),
                                             Recipient.VibrateState.fromId(callVibrateState),
                                             Util.uri(messageRingtone), Util.uri(callRingtone),
                                             color, defaultSubscriptionId, expireMessages,
                                             Recipient.RegisteredState.fromId(registeredState),
                                             profileKey, systemDisplayName, systemContactPhoto,
                                             systemPhoneLabel, systemContactUri,
                                             signalProfileName, signalProfileAvatar, profileSharing,
                                             notificationChannel,
                                             forceSmsSelection, blocksCommunityMessageRequests));
  }

  public boolean isAutoDownloadFlagSet(Recipient recipient) {
    SQLiteDatabase db = getReadableDatabase();
    Cursor cursor = db.query(TABLE_NAME, new String[]{ AUTO_DOWNLOAD }, ADDRESS+" = ?", new String[]{ recipient.getAddress().toString() }, null, null, null);
    boolean flagUnset = false;
    try {
      if (cursor.moveToFirst()) {
        // flag isn't set if it is -1
        flagUnset = cursor.getInt(cursor.getColumnIndexOrThrow(AUTO_DOWNLOAD)) == -1;
      }
    } finally {
      cursor.close();
    }
    // negate result (is flag set)
    return !flagUnset;
  }

  public void setColor(@NonNull Recipient recipient, @NonNull MaterialColor color) {
    ContentValues values = new ContentValues();
    values.put(COLOR, color.serialize());
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setColor(color);
    notifyRecipientListeners();
  }

  public void setDefaultSubscriptionId(@NonNull Recipient recipient, int defaultSubscriptionId) {
    ContentValues values = new ContentValues();
    values.put(DEFAULT_SUBSCRIPTION_ID, defaultSubscriptionId);
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setDefaultSubscriptionId(Optional.of(defaultSubscriptionId));
    notifyRecipientListeners();
  }

  public void setForceSmsSelection(@NonNull Recipient recipient, boolean forceSmsSelection) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(FORCE_SMS_SELECTION, forceSmsSelection ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setForceSmsSelection(forceSmsSelection);
    notifyRecipientListeners();
  }

  public boolean getApproved(@NonNull Address address) {
    SQLiteDatabase db = getReadableDatabase();
    try (Cursor cursor = db.query(TABLE_NAME, new String[]{APPROVED}, ADDRESS + " = ?", new String[]{address.toString()}, null, null, null)) {
      if (cursor != null && cursor.moveToNext()) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(APPROVED)) == 1;
      }
    }
    return false;
  }

  public void setApproved(@NonNull Recipient recipient, boolean approved) {
    ContentValues values = new ContentValues();
    values.put(APPROVED, approved ? 1 : 0);
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setApproved(approved);
    notifyRecipientListeners();
  }

  public void setApprovedMe(@NonNull Recipient recipient, boolean approvedMe) {
    ContentValues values = new ContentValues();
    values.put(APPROVED_ME, approvedMe ? 1 : 0);
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setHasApprovedMe(approvedMe);
    notifyRecipientListeners();
  }

  public void setBlocked(@NonNull Iterable<Recipient> recipients, boolean blocked) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues values = new ContentValues();
      values.put(BLOCK, blocked ? 1 : 0);
      for (Recipient recipient : recipients) {
        db.update(TABLE_NAME, values, ADDRESS + " = ?", new String[]{recipient.getAddress().toString()});
        recipient.resolve().setBlocked(blocked);
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    notifyRecipientListeners();
  }

  // Delete a recipient with the given address from the database
  public void deleteRecipient(@NonNull String recipientAddress) {
    SQLiteDatabase db = getWritableDatabase();
    int rowCount = db.delete(TABLE_NAME, ADDRESS + " = ?", new String[] { recipientAddress });
    if (rowCount == 0) { Log.w(TAG, "Could not find to delete recipient with address: " + recipientAddress); }
    notifyRecipientListeners();
  }

  public void setAutoDownloadAttachments(@NonNull Recipient recipient, boolean shouldAutoDownloadAttachments) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues values = new ContentValues();
      values.put(AUTO_DOWNLOAD, shouldAutoDownloadAttachments ? 1 : 0);
      db.update(TABLE_NAME, values, ADDRESS+ " = ?", new String[]{recipient.getAddress().toString()});
      recipient.resolve().setAutoDownloadAttachments(shouldAutoDownloadAttachments);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    notifyRecipientListeners();
  }

  public void setMuted(@NonNull Recipient recipient, long until) {
    ContentValues values = new ContentValues();
    values.put(MUTE_UNTIL, until);
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setMuted(until);
    notifyRecipientListeners();
  }

  /**
   *
   * @param recipient to modify notifications for
   * @param notifyType the new notification type {@link #NOTIFY_TYPE_ALL}, {@link #NOTIFY_TYPE_MENTIONS} or {@link #NOTIFY_TYPE_NONE}
   */
  public void setNotifyType(@NonNull Recipient recipient, int notifyType) {
    ContentValues values = new ContentValues();
    values.put(NOTIFY_TYPE, notifyType);
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setNotifyType(notifyType);
    notifyConversationListListeners();
    notifyRecipientListeners();
  }

  public void setProfileKey(@NonNull Recipient recipient, @Nullable byte[] profileKey) {
    ContentValues values = new ContentValues(1);
    values.put(PROFILE_KEY, profileKey == null ? null : Base64.encodeBytes(profileKey));
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setProfileKey(profileKey);
    notifyRecipientListeners();
  }

  public void setProfileAvatar(@NonNull Recipient recipient, @Nullable String profileAvatar) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SESSION_PROFILE_AVATAR, profileAvatar);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setProfileAvatar(profileAvatar);
    notifyRecipientListeners();
  }

  public void setProfileName(@NonNull Recipient recipient, @Nullable String profileName) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SYSTEM_DISPLAY_NAME, profileName);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setName(profileName);
    recipient.resolve().setProfileName(profileName);
    notifyRecipientListeners();
  }

  public void setProfileSharing(@NonNull Recipient recipient, boolean enabled) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(PROFILE_SHARING, enabled ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.setProfileSharing(enabled);
    notifyRecipientListeners();
  }

  public void setNotificationChannel(@NonNull Recipient recipient, @Nullable String notificationChannel) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(NOTIFICATION_CHANNEL, notificationChannel);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.setNotificationChannel(notificationChannel);
    notifyRecipientListeners();
  }

  public void setRegistered(@NonNull Recipient recipient, RegisteredState registeredState) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(REGISTERED, registeredState.getId());
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.setRegistered(registeredState);
    notifyRecipientListeners();
  }

  public void setBlocksCommunityMessageRequests(@NonNull Recipient recipient, boolean isBlocked) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(BLOCKS_COMMUNITY_MESSAGE_REQUESTS, isBlocked ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setBlocksCommunityMessageRequests(isBlocked);
    notifyRecipientListeners();
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

  public List<Recipient> getBlockedContacts() {
    SQLiteDatabase database = getReadableDatabase();

    Cursor         cursor   = database.query(TABLE_NAME, new String[] {ID, ADDRESS}, BLOCK + " = 1",
            null, null, null, null, null);

    RecipientReader reader = new RecipientReader(context, cursor);
    List<Recipient> returnList = new ArrayList<>();
    Recipient current;
    while ((current = reader.getNext()) != null) {
      returnList.add(current);
    }
    reader.close();
    return returnList;
  }

  /**
   * Returns a list of all recipients in the database.
   *
   * @return A list of all recipients
   */
  public List<Recipient> getAllRecipients() {
    SQLiteDatabase database = getReadableDatabase();

    Cursor cursor = database.query(TABLE_NAME, new String[] {ID, ADDRESS}, null,
            null, null, null, null, null);

    RecipientReader reader = new RecipientReader(context, cursor);
    List<Recipient> returnList = new ArrayList<>();
    Recipient current;

    while ((current = reader.getNext()) != null) {
      returnList.add(current);
    }

    reader.close();
    return returnList;
  }

  public void setDisappearingState(@NonNull Recipient recipient, @NonNull Recipient.DisappearingState disappearingState) {
    ContentValues values = new ContentValues();
    values.put(DISAPPEARING_STATE, disappearingState.getId());
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setDisappearingState(disappearingState);
    notifyRecipientListeners();
  }

  public static class RecipientReader implements Closeable {

    private final Context context;
    private final Cursor  cursor;

    RecipientReader(Context context, Cursor cursor) {
      this.context = context;
      this.cursor  = cursor;
    }

    public @NonNull Recipient getCurrent() {
      String serialized = cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS));
      return Recipient.from(context, Address.fromSerialized(serialized), false);
    }

    public @Nullable Recipient getNext() {
      if (cursor != null && !cursor.moveToNext()) {
        return null;
      }

      return getCurrent();
    }

    public void close() {
      cursor.close();
    }
  }
}
