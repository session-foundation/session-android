/*
 * Copyright (C) 2012 Moxie Marlinpsike
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

import org.session.libsession.messaging.MessagingModuleConfiguration;
import org.session.libsession.messaging.utilities.UpdateMessageData;
import org.session.libsession.utilities.recipients.Recipient;
import org.thoughtcrime.securesms.database.model.content.MessageContent;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import network.loki.messenger.libsession_util.protocol.ProFeature;

/**
 * The base class for message record models that are displayed in
 * conversations, as opposed to models that are displayed in a thread list.
 * Encapsulates the shared data between both SMS and MMS messages.
 *
 * @author Moxie Marlinspike
 *
 */
public abstract class MessageRecord extends DisplayRecord {
  private final Recipient individualRecipient;
  private final long                      expiresIn;
  private final long                      expireStarted;
  public  final long                      id;
  private final List<ReactionRecord>      reactions;
  private final boolean                   hasMention;

  @Nullable
  private UpdateMessageData               groupUpdateMessage;
  public final Set<ProFeature>                   proFeatures;

  public abstract boolean isMms();
  public abstract boolean isMmsNotification();

  public final MessageId getMessageId() {
    return new MessageId(getId(), isMms());
  }

  MessageRecord(long id, String body, Recipient conversationRecipient,
                Recipient individualRecipient,
                long dateSent, long dateReceived, long threadId,
                int deliveryStatus, int deliveryReceiptCount, long type,
                long expiresIn, long expireStarted,
                int readReceiptCount, List<ReactionRecord> reactions, boolean hasMention,
                @Nullable MessageContent messageContent,
                Set<ProFeature> proFeatures)
  {
    super(body, conversationRecipient, dateSent, dateReceived,
      threadId, deliveryStatus, deliveryReceiptCount, type, readReceiptCount, messageContent);
    this.id                  = id;
    this.individualRecipient = individualRecipient;
    this.expiresIn           = expiresIn;
    this.expireStarted       = expireStarted;
    this.reactions           = reactions;
    this.hasMention          = hasMention;
    this.proFeatures = proFeatures;
  }

  public long getId() {
    return id;
  }
  public long getTimestamp() {
    return getDateSent();
  }
  public Recipient getIndividualRecipient() {
    return individualRecipient;
  }
  public long getType() {
    return type;
  }

  public long getExpiresIn() {
    return expiresIn;
  }
  public long getExpireStarted() { return expireStarted; }

  public boolean getHasMention() { return hasMention; }

  public boolean isMediaPending() {
    return false;
  }

  /**
   * @return Decoded group update message. Only valid if the message is a group update message.
   */
  @Nullable
  public UpdateMessageData getGroupUpdateMessage() {
    if (isGroupUpdateMessage()) {
      groupUpdateMessage = UpdateMessageData.Companion.fromJSON(
              MessagingModuleConfiguration.getShared().getJson(),
              getBody()
      );
    }

    return groupUpdateMessage;
  }

  public boolean isGroupExpirationTimerUpdate() {
    return getGroupUpdateMessage() != null &&
            getGroupUpdateMessage().getKind() instanceof UpdateMessageData.Kind.GroupExpirationUpdated;
  }

    @Override
  public boolean equals(Object other) {
    return other instanceof MessageRecord
            && ((MessageRecord) other).getId() == getId()
            && ((MessageRecord) other).isMms() == isMms();
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, isMms());
  }

  public @NonNull List<ReactionRecord> getReactions() {
    return reactions;
  }

}
