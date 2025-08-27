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
import android.os.Bundle;

import androidx.core.app.RemoteInput;

import org.session.libsession.messaging.messages.signal.OutgoingMediaMessage;
import org.session.libsession.messaging.messages.signal.OutgoingTextMessage;
import org.session.libsession.messaging.messages.visible.VisibleMessage;
import org.session.libsession.messaging.sending_receiving.MessageSender;
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier;
import org.session.libsession.snode.SnodeAPI;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.AddressKt;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.database.MarkedMessageInfo;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.RecipientRepository;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.mms.MmsException;

import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import network.loki.messenger.libsession_util.util.ExpiryMode;

/**
 * Get the response text from the Android Auto and sends an message as a reply
 */
@AndroidEntryPoint
public class AndroidAutoReplyReceiver extends BroadcastReceiver {

  public static final String TAG             = AndroidAutoReplyReceiver.class.getSimpleName();
  public static final String REPLY_ACTION    = "network.loki.securesms.notifications.ANDROID_AUTO_REPLY";
  public static final String ADDRESS_EXTRA   = "car_address";
  public static final String VOICE_REPLY_KEY = "car_voice_reply_key";
  public static final String THREAD_ID_EXTRA = "car_reply_thread_id";

  @Inject
  ThreadDatabase threadDatabase;

  @Inject
  RecipientRepository recipientRepository;

  @Inject
  MmsDatabase mmsDatabase;

  @Inject
  SmsDatabase smsDatabase;

  @Inject
  MessageNotifier messageNotifier;

  @SuppressLint("StaticFieldLeak")
  @Override
  public void onReceive(final Context context, Intent intent)
  {
    if (!REPLY_ACTION.equals(intent.getAction())) return;

    Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);

    if (remoteInput == null) return;

    final Address      address      = intent.getParcelableExtra(ADDRESS_EXTRA);
    final long         threadId     = intent.getLongExtra(THREAD_ID_EXTRA, -1);
    final CharSequence responseText = getMessageText(intent);

    if (responseText != null) {
      new AsyncTask<Void, Void, Void>() {
        @Override
        protected Void doInBackground(Void... params) {

          long replyThreadId;

          if (threadId == -1) {
            replyThreadId = threadDatabase.getOrCreateThreadIdFor(address);
          } else {
            replyThreadId = threadId;
          }

          VisibleMessage message = new VisibleMessage();
          message.setText(responseText.toString());
          message.setSentTimestamp(SnodeAPI.getNowWithOffset());
          MessageSender.send(message, address);
          ExpiryMode expiryMode = recipientRepository.getRecipientSync(address).getExpiryMode();
          long expiresInMillis = expiryMode.getExpiryMillis();
          long expireStartedAt = expiryMode instanceof ExpiryMode.AfterSend ? message.getSentTimestamp() : 0L;

          if (AddressKt.isGroupOrCommunity(address)) {
            Log.w("AndroidAutoReplyReceiver", "GroupRecipient, Sending media message");
            OutgoingMediaMessage reply = OutgoingMediaMessage.from(message, address, Collections.emptyList(), null, null, expiresInMillis, 0);
            try {
              mmsDatabase.insertMessageOutbox(reply, replyThreadId, false, true);
            } catch (MmsException e) {
              Log.w(TAG, e);
            }
          } else {
            Log.w("AndroidAutoReplyReceiver", "Sending regular message ");
            OutgoingTextMessage reply = OutgoingTextMessage.from(message, address, expiresInMillis, expireStartedAt);
            smsDatabase.insertMessageOutbox(replyThreadId, reply, false, SnodeAPI.getNowWithOffset(), true);
          }

          List<MarkedMessageInfo> messageIds = threadDatabase.setRead(replyThreadId, true);

          messageNotifier.updateNotification(context);
          MarkReadReceiver.process(context, messageIds);

          return null;
        }
      }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
  }

  private CharSequence getMessageText(Intent intent) {
    Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
    if (remoteInput != null) {
      return remoteInput.getCharSequence(VOICE_REPLY_KEY);
    }
    return null;
  }

}
