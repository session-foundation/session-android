package org.thoughtcrime.securesms.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.thoughtcrime.securesms.dependencies.AppComponent;
import dagger.hilt.EntryPoints;

public class LocaleChangedReceiver extends BroadcastReceiver {

  @Override
  public void onReceive(Context context, Intent intent) {
    EntryPoints.get(context.getApplicationContext(), AppComponent.class).getNotificationChannels().create();
  }
}
