/**
 * Copyright (C) 2012 Moxie Marlinspike
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
package org.thoughtcrime.securesms.database.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview;
import org.session.libsession.utilities.recipients.Recipient;
import org.thoughtcrime.securesms.database.SmsDatabase.Status;
import org.thoughtcrime.securesms.database.model.content.MessageContent;
import org.thoughtcrime.securesms.mms.SlideDeck;

import java.util.List;
import java.util.Set;

import network.loki.messenger.libsession_util.protocol.ProFeature;

/**
 * Represents the message record model for MMS messages that contain
 * media (ie: they've been downloaded).
 *
 * @author Moxie Marlinspike
 *
 */

public class MediaMmsMessageRecord extends MmsMessageRecord {

  public MediaMmsMessageRecord(long id, Recipient conversationRecipient,
                               Recipient individualRecipient,
                               long dateSent, long dateReceived, int deliveryReceiptCount,
                               long threadId, String body,
                               @NonNull SlideDeck slideDeck,
                               long mailbox,
                               long expiresIn, long expireStarted, int readReceiptCount,
                               @Nullable Quote quote,
                               @NonNull List<LinkPreview> linkPreviews,
                               @NonNull List<ReactionRecord> reactions, boolean hasMention,
                               @Nullable MessageContent messageContent,
                               Set<ProFeature> proFeatures)
  {
    super(id, body, conversationRecipient, individualRecipient, dateSent,
      dateReceived, threadId, Status.STATUS_NONE, deliveryReceiptCount, mailbox,
      expiresIn, expireStarted, slideDeck, readReceiptCount, quote,
            linkPreviews, reactions, hasMention, messageContent, proFeatures);
  }

    @Override
  public boolean isMmsNotification() {
    return false;
  }

}
