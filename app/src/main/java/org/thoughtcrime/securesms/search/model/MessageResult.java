package org.thoughtcrime.securesms.search.model;

import androidx.annotation.NonNull;

import org.session.libsession.utilities.recipients.Recipient;

import java.util.Objects;

/**
 * Represents a search result for a message.
 */
public class MessageResult {

  public final Recipient conversationRecipient;
  public final Recipient messageRecipient;
  public final String    bodySnippet;
  public final long      threadId;
  public final long      sentTimestampMs;

  public MessageResult(@NonNull Recipient conversationRecipient,
                       @NonNull Recipient messageRecipient,
                       @NonNull String bodySnippet,
                       long threadId,
                       long sentTimestampMs)
  {
    this.conversationRecipient = conversationRecipient;
    this.messageRecipient      = messageRecipient;
    this.bodySnippet           = bodySnippet;
    this.threadId              = threadId;
    this.sentTimestampMs       = sentTimestampMs;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof MessageResult that)) return false;
      return threadId == that.threadId && sentTimestampMs == that.sentTimestampMs && Objects.equals(conversationRecipient, that.conversationRecipient) && Objects.equals(messageRecipient, that.messageRecipient) && Objects.equals(bodySnippet, that.bodySnippet);
  }

  @Override
  public int hashCode() {
    return Objects.hash(conversationRecipient, messageRecipient, bodySnippet, threadId, sentTimestampMs);
  }
}
