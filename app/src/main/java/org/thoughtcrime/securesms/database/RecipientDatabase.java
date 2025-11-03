package org.thoughtcrime.securesms.database;

import static org.session.libsession.utilities.GroupUtil.COMMUNITY_PREFIX;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import com.annimon.stream.Stream;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.GroupUtil;
import org.session.libsignal.utilities.AccountId;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;

import java.util.List;

import javax.inject.Provider;

import kotlin.Triple;
import okhttp3.HttpUrl;

/**
 * Note that you should not use this table anymore, use {@link RecipientSettingsDatabase} instead.
 */
@Deprecated(forRemoval = true)
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
  
  private static final String UNIDENTIFIED_ACCESS_MODE = "unidentified_access_mode";
  
  private static final String FORCE_SMS_SELECTION      = "force_sms_selection";
  private static final String NOTIFY_TYPE              = "notify_type"; // all, mentions only, none
  
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

  public static void migrateOldCommunityAddresses(final SQLiteDatabase db) {
    final String query = "SELECT " + ID + ", " + ADDRESS + " FROM " + TABLE_NAME;

    try (final Cursor cursor = db.rawQuery(query)) {
      while (cursor.moveToNext()) {
        final long id = cursor.getLong(0);
        final String address = cursor.getString(1);
        final String newAddress;

        try {
          if (address.startsWith(GroupUtil.COMMUNITY_PREFIX)) {
            // Fill out the real community address from the database
            final String communityQuery = "SELECT public_chat ->>'$.server', public_chat ->> '$.room' FROM loki_public_chat_database WHERE thread_id IN (SELECT " +
                    ThreadDatabase.ID + " FROM " + ThreadDatabase.TABLE_NAME + " WHERE " + ThreadDatabase.ADDRESS + " = ? LIMIT 1)";

            try (final Cursor communityCursor = db.rawQuery(communityQuery, address)) {
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
          Log.i(TAG, "Migrating recipient settings ID = " + id);
          ContentValues contentValues = new ContentValues(1);
          contentValues.put(ADDRESS, newAddress);
          db.update(TABLE_NAME, contentValues, ID + " = ?", new String[]{String.valueOf(id)});
        }
      }
    }

  }

  public static final int NOTIFY_TYPE_ALL = 0;
  public static final int NOTIFY_TYPE_MENTIONS = 1;
  public static final int NOTIFY_TYPE_NONE = 2;

  public RecipientDatabase(Context context, Provider<SQLCipherOpenHelper> databaseHelper) {
    super(context, databaseHelper);
  }
}
