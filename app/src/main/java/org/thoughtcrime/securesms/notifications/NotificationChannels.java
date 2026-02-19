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
import org.session.libsession.utilities.ServiceUtil;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.preferences.NotificationPreferences;
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

  private final Context context;
  private final PreferenceStorage preferenceStorage;

  @Inject
  public NotificationChannels(@ApplicationContext Context context, PreferenceStorage preferenceStorage) {
    this.context = context;
    this.preferenceStorage = preferenceStorage;
  }

  /**
   * Ensures all of the notification channels are created. No harm in repeat calls. Call is safely
   * ignored for API < 26.
   */
  public synchronized void create() {
    NotificationManager notificationManager = ServiceUtil.getNotificationManager(context);

    int oldVersion = preferenceStorage.get(NotificationPreferences.CHANNEL_VERSION);
    if (oldVersion != VERSION) {
      onUpgrade(notificationManager, oldVersion, VERSION);
      preferenceStorage.set(NotificationPreferences.CHANNEL_VERSION, VERSION);
    }

    onCreate(notificationManager);
  }

  /**
   * @return The channel ID for the default messages channel.
   */
  public synchronized @NonNull String getMessagesChannel() {
    return getMessagesChannelId(preferenceStorage.get(NotificationPreferences.MESSAGES_CHANNEL_VERSION));
  }


  /**
   * @return The message ringtone set for the default message channel.
   */
  public synchronized @NonNull Uri getMessageRingtone() {
    Uri sound = ServiceUtil.getNotificationManager(context).getNotificationChannel(getMessagesChannel()).getSound();
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
    return ServiceUtil.getNotificationManager(context).getNotificationChannel(getMessagesChannel()).shouldVibrate();
  }

  /**
   * Sets the vibrate property for the default message channel.
   */
  public synchronized void updateMessageVibrate(boolean enabled) {
    Log.i(TAG, "Updating default vibrate with value: " + enabled);

    updateMessageChannel(channel -> channel.enableVibration(enabled));
  }


  private void onCreate(@NonNull NotificationManager notificationManager) {
    NotificationChannelGroup messagesGroup = new NotificationChannelGroup(CATEGORY_MESSAGES, context.getResources().getString(R.string.messages));
    notificationManager.createNotificationChannelGroup(messagesGroup);

    NotificationChannel messages     = new NotificationChannel(getMessagesChannel(), context.getString(R.string.theDefault), NotificationManager.IMPORTANCE_HIGH);
    NotificationChannel calls        = new NotificationChannel(CALLS, context.getString(R.string.callsSettings), NotificationManager.IMPORTANCE_HIGH);
    NotificationChannel failures     = new NotificationChannel(FAILURES, context.getString(R.string.failures), NotificationManager.IMPORTANCE_HIGH);
    NotificationChannel lockedStatus = new NotificationChannel(LOCKED_STATUS, context.getString(R.string.lockAppStatus), NotificationManager.IMPORTANCE_LOW);
    NotificationChannel other        = new NotificationChannel(OTHER, context.getString(R.string.other), NotificationManager.IMPORTANCE_LOW);

    messages.setGroup(CATEGORY_MESSAGES);

    messages.enableVibration(preferenceStorage.get(NotificationPreferences.VIBRATE_ENABLED));
    messages.setSound(Uri.parse(preferenceStorage.get(NotificationPreferences.RINGTONE)), getRingtoneAudioAttributes());
    setLedPreference(messages, preferenceStorage.get(NotificationPreferences.LED_COLOR));

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

  private void onUpgrade(@NonNull NotificationManager notificationManager, int oldVersion, int newVersion) {
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

  private void setLedPreference(@NonNull NotificationChannel channel, @NonNull Integer ledColor) {
    if ("none".equals(ledColor)) {
      channel.enableLights(false);
    } else {
      channel.enableLights(true);
      channel.setLightColor(ledColor);
    }
  }


  private @NonNull String generateChannelIdFor(@NonNull Address address) {
    return CONTACT_PREFIX + address.toString() + "_" + System.currentTimeMillis();
  }

  private @NonNull NotificationChannel copyChannel(@NonNull NotificationChannel original, @NonNull String id) {
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

  private String getMessagesChannelId(int version) {
    return MESSAGES_PREFIX + version;
  }


  private void updateMessageChannel(@NonNull ChannelUpdater updater) {
    NotificationManager notificationManager = ServiceUtil.getNotificationManager(context);
    int existingVersion                     = preferenceStorage.get(NotificationPreferences.MESSAGES_CHANNEL_VERSION);
    int newVersion                          = existingVersion + 1;

    Log.i(TAG, "Updating message channel from version " + existingVersion + " to " + newVersion);
    if (updateExistingChannel(notificationManager, getMessagesChannelId(existingVersion), getMessagesChannelId(newVersion), updater)) {
      preferenceStorage.set(NotificationPreferences.MESSAGES_CHANNEL_VERSION, newVersion);
    } else {
      onCreate(notificationManager);
    }
  }

  private boolean updateExistingChannel(@NonNull NotificationManager notificationManager,
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

  private AudioAttributes getRingtoneAudioAttributes() {
    return new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
        .build();
  }

  private boolean channelExists(@Nullable NotificationChannel channel) {
    return channel != null && !NotificationChannel.DEFAULT_CHANNEL_ID.equals(channel.getId());
  }

  private interface ChannelUpdater {
    void update(@NonNull NotificationChannel channel);
  }
}
