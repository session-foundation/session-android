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

package org.thoughtcrime.securesms.notifications;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;

import org.session.libsession.network.SnodeClock;
import org.thoughtcrime.securesms.database.Storage;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Marks an Android Auto as read after the driver have listened to it
 */
@AndroidEntryPoint
public class AndroidAutoHeardReceiver extends BroadcastReceiver {

  public static final String TAG              = AndroidAutoHeardReceiver.class.getSimpleName();
  public static final String HEARD_ACTION     = "network.loki.securesms.notifications.ANDROID_AUTO_HEARD";
  public static final String THREAD_IDS_EXTRA = "car_heard_thread_ids";

  @Inject Storage storage;
  @Inject SnodeClock clock;

  @SuppressLint("StaticFieldLeak")
  @Override
  public void onReceive(final Context context, Intent intent)
  {
    if (!HEARD_ACTION.equals(intent.getAction()))
      return;

    final long[] threadIds = intent.getLongArrayExtra(THREAD_IDS_EXTRA);

    if (threadIds != null) {
      // Notification cancellation is handled reactively by NotificationProcessor when lastSeen advances.
      new AsyncTask<Void, Void, Void>() {
        @Override
        protected Void doInBackground(Void... params) {
            long now = clock.currentTimeMillis();
            for (long threadId : threadIds) {
                storage.updateConversationLastSeenIfNeeded(threadId, now);
            }

            return null;
        }
      }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
  }
}
