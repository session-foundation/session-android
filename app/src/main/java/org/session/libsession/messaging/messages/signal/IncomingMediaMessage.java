package org.session.libsession.messaging.messages.signal;

import org.jspecify.annotations.Nullable;
import org.session.libsession.messaging.messages.visible.VisibleMessage;
import org.session.libsession.messaging.sending_receiving.attachments.Attachment;
import org.session.libsession.messaging.sending_receiving.attachments.PointerAttachment;
import org.session.libsession.messaging.sending_receiving.data_extraction.DataExtractionNotificationInfoMessage;
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview;
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.Contact;
import org.session.libsession.utilities.GroupUtil;
import org.session.libsignal.messages.SignalServiceAttachment;
import org.session.libsignal.messages.SignalServiceGroup;
import org.session.libsignal.utilities.Hex;
import org.session.libsignal.utilities.guava.Optional;
import org.thoughtcrime.securesms.database.model.content.MessageContent;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class IncomingMediaMessage {

  private final Address       from;
  private final Address       groupId;
  private final String        body;
  private final boolean       push;
  private final long          sentTimeMillis;
  private final int           subscriptionId;
  private final long          expiresIn;
  private final long          expireStartedAt;
  private final boolean       messageRequestResponse;
  private final boolean       hasMention;
  @Nullable
  private final MessageContent messageContent;

  private final DataExtractionNotificationInfoMessage dataExtractionNotification;
  private final QuoteModel                            quote;

  private final List<Attachment>  attachments    = new LinkedList<>();
  private final List<Contact>     sharedContacts = new LinkedList<>();
  private final List<LinkPreview> linkPreviews   = new LinkedList<>();

  public IncomingMediaMessage(Address from,
                              long sentTimeMillis,
                              int subscriptionId,
                              long expiresIn,
                              long expireStartedAt,
                              boolean messageRequestResponse,
                              boolean hasMention,
                              Optional<String> body,
                              Optional<SignalServiceGroup> group,
                              Optional<List<SignalServiceAttachment>> attachments,
                              @Nullable MessageContent messageContent,
                              Optional<QuoteModel> quote,
                              Optional<List<Contact>> sharedContacts,
                              Optional<List<LinkPreview>> linkPreviews,
                              Optional<DataExtractionNotificationInfoMessage> dataExtractionNotification)
  {
    this.messageContent = messageContent;
    this.push                       = true;
    this.from                       = from;
    this.sentTimeMillis             = sentTimeMillis;
    this.body                       = body.orNull();
    this.subscriptionId             = subscriptionId;
    this.expiresIn                  = expiresIn;
    this.expireStartedAt            = expireStartedAt;
    this.dataExtractionNotification = dataExtractionNotification.orNull();
    this.quote                      = quote.orNull();
    this.messageRequestResponse     = messageRequestResponse;
    this.hasMention                 = hasMention;

    if (group.isPresent()) {
      SignalServiceGroup groupObject = group.get();
      if (groupObject.isGroupV2()) {
        // new groupv2 03..etc..
        this.groupId = Address.fromSerialized(Hex.toStringCondensed(groupObject.getGroupId()));
      } else {
        // legacy group or community
        this.groupId = Address.fromSerialized(GroupUtil.getEncodedId(group.get()));
      }
    } else {
      this.groupId = null;
    }

    this.attachments.addAll(PointerAttachment.forPointers(attachments));
    this.sharedContacts.addAll(sharedContacts.or(Collections.emptyList()));
    this.linkPreviews.addAll(linkPreviews.or(Collections.emptyList()));
  }

  public static IncomingMediaMessage from(VisibleMessage message,
                                          Address from,
                                          long expiresIn,
                                          long expireStartedAt,
                                          Optional<SignalServiceGroup> group,
                                          List<SignalServiceAttachment> attachments,
                                          Optional<QuoteModel> quote,
                                          Optional<List<LinkPreview>> linkPreviews)
  {
    return new IncomingMediaMessage(from, message.getSentTimestamp(), -1, expiresIn, expireStartedAt,
            false, message.getHasMention(), Optional.fromNullable(message.getText()),
            group, Optional.fromNullable(attachments), null, quote, Optional.absent(), linkPreviews, Optional.absent());
  }

  public int getSubscriptionId() {
    return subscriptionId;
  }

  public String getBody() {
    return body;
  }

  public List<Attachment> getAttachments() {
    return attachments;
  }

  public Address getFrom() {
    return from;
  }

  public Address getGroupId() {
    return groupId;
  }

  public @Nullable MessageContent getMessageContent() {
    return messageContent;
  }

  public boolean isPushMessage() {
    return push;
  }

  public long getSentTimeMillis() {
    return sentTimeMillis;
  }

  public long getExpiresIn() {
    return expiresIn;
  }

  public long getExpireStartedAt() {
    return expireStartedAt;
  }

  public boolean isGroupMessage() {
    return groupId != null;
  }

  public boolean hasMention() {
    return hasMention;
  }

  public boolean isScreenshotDataExtraction() {
    if (dataExtractionNotification == null) return false;
    else {
      return dataExtractionNotification.getKind() == DataExtractionNotificationInfoMessage.Kind.SCREENSHOT;
    }
  }

  public boolean isMediaSavedDataExtraction() {
    if (dataExtractionNotification == null) return false;
    else {
      return dataExtractionNotification.getKind() == DataExtractionNotificationInfoMessage.Kind.MEDIA_SAVED;
    }
  }

  public QuoteModel getQuote() {
    return quote;
  }

  public List<Contact> getSharedContacts() {
    return sharedContacts;
  }

  public List<LinkPreview> getLinkPreviews() {
    return linkPreviews;
  }

  public boolean isMessageRequestResponse() {
    return messageRequestResponse;
  }
}
