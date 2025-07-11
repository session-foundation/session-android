package org.thoughtcrime.securesms.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.thoughtcrime.securesms.ApplicationContext;

/**
 * This is a [BroadcastReceiver] that receives the alarm event from the OS. The
 * alarm is most likely set off by the disappearing message handling, which only requires the apps
 * to be alive. So the whole purpose of this receiver is just to bring the app up (or keep it alive)
 * when the alarm goes off. The disappearing message handling will pick themselves up from the
 * previous states.
 */
public class ExpirationListener extends BroadcastReceiver {

  @Override
  public void onReceive(Context context, Intent intent) {
  }

  public static void setAlarm(Context context, long waitTimeMillis) {
    Intent        intent        = new Intent(context, ExpirationListener.class);
    PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    AlarmManager  alarmManager  = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);

    alarmManager.cancel(pendingIntent);
    alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + waitTimeMillis, pendingIntent);
  }
}
