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

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;
import androidx.annotation.Nullable;

import org.session.libsession.messaging.MessagingModuleConfiguration;
import org.session.libsession.messaging.calls.CallMessageType;
import org.session.libsession.messaging.sending_receiving.data_extraction.DataExtractionNotificationInfoMessage;
import org.session.libsession.messaging.utilities.UpdateMessageBuilder;
import org.session.libsession.messaging.utilities.UpdateMessageData;
import org.session.libsession.utilities.IdentityKeyMismatch;
import org.session.libsession.utilities.NetworkFailure;
import org.session.libsession.utilities.ThemeUtil;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsignal.utilities.AccountId;
import org.thoughtcrime.securesms.dependencies.DatabaseComponent;

import network.loki.messenger.R;

/**
 * The base class for message record models that are displayed in
 * conversations, as opposed to models that are displayed in a thread list.
 * Encapsulates the shared data between both SMS and MMS messages.
 *
 * @author Moxie Marlinspike
 *
 */
public abstract class MessageRecord extends DisplayRecord {
  private final Recipient                 individualRecipient;
  private final List<IdentityKeyMismatch> mismatches;
  private final List<NetworkFailure>      networkFailures;
  private final long                      expiresIn;
  private final long                      expireStarted;
  public  final long                      id;
  private final List<ReactionRecord>      reactions;
  private final boolean                   hasMention;

  @Nullable
  private UpdateMessageData               groupUpdateMessage;

  public final boolean isNotDisappearAfterRead() {
    return expireStarted == getTimestamp();
  }

  public abstract boolean isMms();
  public abstract boolean isMmsNotification();

  public final MessageId getMessageId() {
    return new MessageId(getId(), isMms());
  }

  MessageRecord(long id, String body, Recipient conversationRecipient,
    Recipient individualRecipient,
    long dateSent, long dateReceived, long threadId,
    int deliveryStatus, int deliveryReceiptCount, long type,
    List<IdentityKeyMismatch> mismatches,
    List<NetworkFailure> networkFailures,
    long expiresIn, long expireStarted,
    int readReceiptCount, List<ReactionRecord> reactions, boolean hasMention)
  {
    super(body, conversationRecipient, dateSent, dateReceived,
      threadId, deliveryStatus, deliveryReceiptCount, type, readReceiptCount);
    this.id                  = id;
    this.individualRecipient = individualRecipient;
    this.mismatches          = mismatches;
    this.networkFailures     = networkFailures;
    this.expiresIn           = expiresIn;
    this.expireStarted       = expireStarted;
    this.reactions           = reactions;
    this.hasMention          = hasMention;
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
  public List<NetworkFailure> getNetworkFailures() {
    return networkFailures;
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
      groupUpdateMessage = UpdateMessageData.Companion.fromJSON(getBody());
    }

    return groupUpdateMessage;
  }

  @Override
  public CharSequence getDisplayBody(@NonNull Context context) {
    if (isGroupUpdateMessage()) {
      UpdateMessageData updateMessageData = getGroupUpdateMessage();
      Recipient groupRecipient = DatabaseComponent.get(context).threadDatabase().getRecipientForThreadId(getThreadId());

      if (updateMessageData == null || groupRecipient == null) {
        return "";
      }

      SpannableString text = new SpannableString(UpdateMessageBuilder.buildGroupUpdateMessage(
              context,
              groupRecipient.isGroupV2Recipient() ? new AccountId(groupRecipient.getAddress().toString()) : null, // accountId is only used for GroupsV2
              updateMessageData,
              MessagingModuleConfiguration.getShared().getConfigFactory(),
              isOutgoing(),
              getTimestamp(),
              getExpireStarted())
      );

      if (updateMessageData.isGroupErrorQuitKind()) {
        text.setSpan(new ForegroundColorSpan(ThemeUtil.getThemedColor(context, R.attr.danger)), 0, text.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
      } else if (updateMessageData.isGroupLeavingKind()) {
        text.setSpan(new ForegroundColorSpan(ThemeUtil.getThemedColor(context, android.R.attr.textColorTertiary)), 0, text.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
      }

      return text;
    } else if (isExpirationTimerUpdate()) {
      int seconds = (int) (getExpiresIn() / 1000);
      boolean isGroup = DatabaseComponent.get(context).threadDatabase().getRecipientForThreadId(getThreadId()).isGroupOrCommunityRecipient();
      return new SpannableString(UpdateMessageBuilder.INSTANCE.buildExpirationTimerMessage(context, seconds, isGroup, getIndividualRecipient().getAddress().toString(), isOutgoing(), getTimestamp(), expireStarted));
    } else if (isDataExtractionNotification()) {
      if (isScreenshotNotification()) return new SpannableString((UpdateMessageBuilder.INSTANCE.buildDataExtractionMessage(context, DataExtractionNotificationInfoMessage.Kind.SCREENSHOT, getIndividualRecipient().getAddress().toString())));
      else if (isMediaSavedNotification()) return new SpannableString((UpdateMessageBuilder.INSTANCE.buildDataExtractionMessage(context, DataExtractionNotificationInfoMessage.Kind.MEDIA_SAVED, getIndividualRecipient().getAddress().toString())));
    } else if (isCallLog()) {
      CallMessageType callType;
      if (isIncomingCall()) {
        callType = CallMessageType.CALL_INCOMING;
      } else if (isOutgoingCall()) {
        callType = CallMessageType.CALL_OUTGOING;
      } else if (isMissedCall()) {
        callType = CallMessageType.CALL_MISSED;
      } else {
        callType = CallMessageType.CALL_FIRST_MISSED;
      }
      return new SpannableString(UpdateMessageBuilder.INSTANCE.buildCallMessage(context, callType, getIndividualRecipient().getAddress().toString()));
    }

    return new SpannableString(getBody());
  }

  public boolean isGroupExpirationTimerUpdate() {
    if (!isGroupUpdateMessage()) {
      return false;
    }

    UpdateMessageData updateMessageData = UpdateMessageData.Companion.fromJSON(getBody());
    return updateMessageData != null && updateMessageData.getKind() instanceof UpdateMessageData.Kind.GroupExpirationUpdated;
  }

  protected SpannableString emphasisAdded(String sequence) {
    SpannableString spannable = new SpannableString(sequence);
    spannable.setSpan(new RelativeSizeSpan(0.9f), 0, sequence.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    spannable.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), 0, sequence.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

    return spannable;
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
