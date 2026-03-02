package org.thoughtcrime.securesms.notifications;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.ThemeUtil;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.preferences.PreferenceKey;
import org.thoughtcrime.securesms.preferences.PreferenceStorage;

import java.util.Arrays;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import network.loki.messenger.BuildConfig;
import network.loki.messenger.R;

@Singleton
public class NotificationChannels {

  private static final String TAG = NotificationChannels.class.getSimpleName();

  private static final int VERSION_MESSAGES_CATEGORY = 2;
  private static final int VERSION_SESSION_CALLS = 3;

  private static final int VERSION = 3;

  private static final String CATEGORY_MESSAGES = "messages";
  private static final String CONTACT_PREFIX    = "contact_";
  private static final String MESSAGES_PREFIX   = "messages_";

  public static final String CALLS         = "calls_v3";
  public static final String FAILURES      = "failures";
  public static final String APP_UPDATES   = "app_updates";
  public static final String BACKUPS       = "backups_v2";
  public static final String LOCKED_STATUS = "locked_status_v2";
  public static final String OTHER         = "other_v2";

  private static final PreferenceKey<Integer> CHANNEL_VERSION = PreferenceKey.Companion.integer("pref_notification_channel_version", 1);
  private static final PreferenceKey<Integer> MESSAGE_CHANNEL_VERSION = PreferenceKey.Companion.integer("pref_notification_messages_channel_version", 1);

  @NonNull final Context context;
  @NonNull final PreferenceStorage prefs;
  @NonNull final NotificationManager notificationManager;

  @Inject
  public NotificationChannels(@NonNull @ApplicationContext Context context, @NonNull PreferenceStorage prefs) {
    this.context = context;
    this.prefs = prefs;

    notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

    int oldVersion = prefs.get(CHANNEL_VERSION);
    if (oldVersion != VERSION) {
      onUpgrade(notificationManager, oldVersion, VERSION);
      prefs.set(CHANNEL_VERSION, VERSION);
    }

    onCreate();
  }

  public void recreate() {
    onCreate();
  }

  /**
   * @return The channel ID for the default messages channel.
   */
  public @NonNull String getMessagesChannel() {
    return getMessagesChannelId(prefs.get(MESSAGE_CHANNEL_VERSION));
  }


  /**
   * @return The message ringtone set for the default message channel.
   */
  public synchronized @NonNull Uri getMessageRingtone() {
    Uri sound = notificationManager.getNotificationChannel(getMessagesChannel()).getSound();
    return sound == null ? Uri.EMPTY : sound;
  }

  /**
   * Update the message ringtone for the default message channel.
   */
  public synchronized void updateMessageRingtone(@Nullable Uri uri) {
    Log.i(TAG, "Updating default message ringtone with URI: " + String.valueOf(uri));

    updateMessageChannel(channel -> {
      channel.setSound(uri == null ? Settings.System.DEFAULT_NOTIFICATION_URI : uri, getRingtoneAudioAttributes());
    });
  }


  /**
   * @return The vibrate settings for the default message channel.
   */
  public synchronized boolean getMessageVibrate() {
    return notificationManager.getNotificationChannel(getMessagesChannel()).shouldVibrate();
  }

  /**
   * Sets the vibrate property for the default message channel.
   */
  public synchronized void updateMessageVibrate(@NonNull Context context, boolean enabled) {
    Log.i(TAG, "Updating default vibrate with value: " + enabled);

    updateMessageChannel(channel -> channel.enableVibration(enabled));
  }


  private void onCreate() {
    NotificationChannelGroup messagesGroup = new NotificationChannelGroup(CATEGORY_MESSAGES, context.getResources().getString(R.string.messages));
    notificationManager.createNotificationChannelGroup(messagesGroup);

    NotificationChannel messages     = new NotificationChannel(getMessagesChannel(), context.getString(R.string.theDefault), NotificationManager.IMPORTANCE_HIGH);
    NotificationChannel calls        = new NotificationChannel(CALLS, context.getString(R.string.callsSettings), NotificationManager.IMPORTANCE_HIGH);
    NotificationChannel failures     = new NotificationChannel(FAILURES, context.getString(R.string.failures), NotificationManager.IMPORTANCE_HIGH);
    NotificationChannel lockedStatus = new NotificationChannel(LOCKED_STATUS, context.getString(R.string.lockAppStatus), NotificationManager.IMPORTANCE_LOW);
    NotificationChannel other        = new NotificationChannel(OTHER, context.getString(R.string.other), NotificationManager.IMPORTANCE_LOW);

    messages.setGroup(CATEGORY_MESSAGES);
    messages.enableVibration(prefs.get(NotificationPreferences.INSTANCE.getENABLE_VIBRATION()));

    String ringtoneUri = prefs.get(NotificationPreferences.INSTANCE.getRINGTONE());
    messages.setSound((ringtoneUri != null) ? Uri.parse(ringtoneUri) : null, getRingtoneAudioAttributes());
    Integer ledColor = prefs.get(NotificationPreferences.INSTANCE.getLED_COLOR());
    setLedPreference(messages, ledColor == 0 ? context.getColor(R.color.accent_green) : ledColor);

    calls.setShowBadge(false);
    calls.setSound(null, null);
    lockedStatus.setShowBadge(false);
    other.setShowBadge(false);

    notificationManager.createNotificationChannels(Arrays.asList(messages, calls, failures, lockedStatus, other));

    if (BuildConfig.PLAY_STORE_DISABLED) {
      NotificationChannel appUpdates = new NotificationChannel(APP_UPDATES, context.getString(R.string.updateApp), NotificationManager.IMPORTANCE_HIGH);
      notificationManager.createNotificationChannel(appUpdates);
    } else {
      notificationManager.deleteNotificationChannel(APP_UPDATES);
    }
  }

  private static void onUpgrade(@NonNull NotificationManager notificationManager, int oldVersion, int newVersion) {
    Log.i(TAG, "Upgrading channels from " + oldVersion + " to " + newVersion);

    if (oldVersion < VERSION_MESSAGES_CATEGORY) {
      notificationManager.deleteNotificationChannel("messages");
      notificationManager.deleteNotificationChannel("calls");
      notificationManager.deleteNotificationChannel("locked_status");
      notificationManager.deleteNotificationChannel("backups");
      notificationManager.deleteNotificationChannel("other");
    } if (oldVersion < VERSION_SESSION_CALLS) {
      notificationManager.deleteNotificationChannel("calls_v2");
    }
  }

  private static void setLedPreference(@NonNull NotificationChannel channel, int ledColor) {
    channel.enableLights(true);
    channel.setLightColor(ledColor);
  }


  private static @NonNull String generateChannelIdFor(@NonNull Address address) {
    return CONTACT_PREFIX + address.toString() + "_" + System.currentTimeMillis();
  }

  private static @NonNull NotificationChannel copyChannel(@NonNull NotificationChannel original, @NonNull String id) {
    NotificationChannel copy = new NotificationChannel(id, original.getName(), original.getImportance());

    copy.setGroup(original.getGroup());
    copy.setSound(original.getSound(), original.getAudioAttributes());
    copy.setBypassDnd(original.canBypassDnd());
    copy.enableVibration(original.shouldVibrate());
    copy.setVibrationPattern(original.getVibrationPattern());
    copy.setLockscreenVisibility(original.getLockscreenVisibility());
    copy.setShowBadge(original.canShowBadge());
    copy.setLightColor(original.getLightColor());
    copy.enableLights(original.shouldShowLights());

    return copy;
  }

  private static String getMessagesChannelId(int version) {
    return MESSAGES_PREFIX + version;
  }


  private void updateMessageChannel(@NonNull ChannelUpdater updater) {
    NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    int existingVersion                     = prefs.get(MESSAGE_CHANNEL_VERSION);
    int newVersion                          = existingVersion + 1;

    Log.i(TAG, "Updating message channel from version " + existingVersion + " to " + newVersion);
    if (updateExistingChannel(notificationManager, getMessagesChannelId(existingVersion), getMessagesChannelId(newVersion), updater)) {
      prefs.set(MESSAGE_CHANNEL_VERSION, newVersion);
    } else {
      recreate();
    }
  }

  private static boolean updateExistingChannel(@NonNull NotificationManager notificationManager,
                                               @NonNull String channelId,
                                               @NonNull String newChannelId,
                                               @NonNull ChannelUpdater updater)
  {
    NotificationChannel existingChannel = notificationManager.getNotificationChannel(channelId);
    if (existingChannel == null) {
      Log.w(TAG, "Tried to update a channel, but it didn't exist.");
      return false;
    }

    notificationManager.deleteNotificationChannel(existingChannel.getId());

    NotificationChannel newChannel = copyChannel(existingChannel, newChannelId);
    updater.update(newChannel);
    notificationManager.createNotificationChannel(newChannel);
    return true;
  }

  private static AudioAttributes getRingtoneAudioAttributes() {
    return new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
        .build();
  }

  private static boolean channelExists(@Nullable NotificationChannel channel) {
    return channel != null && !NotificationChannel.DEFAULT_CHANNEL_ID.equals(channel.getId());
  }

  private interface ChannelUpdater {
    void update(@NonNull NotificationChannel channel);
  }
}
