package org.thoughtcrime.securesms.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class LocaleChangedReceiver extends BroadcastReceiver {
  @Inject NotificationChannels channels;

  @Override
  public void onReceive(Context context, Intent intent) {
    if ("android.intent.action.LOCALE_CHANGED".equals(intent.getAction())) {
      channels.recreate();
    }
  }
}
