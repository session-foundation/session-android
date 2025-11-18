package org.thoughtcrime.securesms.database.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview;
import org.session.libsession.utilities.recipients.Recipient;
import org.thoughtcrime.securesms.database.model.content.MessageContent;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.mms.SlideDeck;

import java.util.ArrayList;
import java.util.List;

public abstract class MmsMessageRecord extends MessageRecord {
    private final @NonNull SlideDeck slideDeck;
    private final @Nullable Quote quote;
    private final @NonNull List<LinkPreview> linkPreviews = new ArrayList<>();

    MmsMessageRecord(long id, String body, Recipient conversationRecipient,
                     Recipient individualRecipient, long dateSent,
                     long dateReceived, long threadId, int deliveryStatus, int deliveryReceiptCount,
                     long type,
                     long expiresIn,
                     long expireStarted, @NonNull SlideDeck slideDeck, int readReceiptCount,
                     @Nullable Quote quote,
                     @NonNull List<LinkPreview> linkPreviews, List<ReactionRecord> reactions, boolean hasMention,
                     @Nullable MessageContent messageContent,
                     long proFeaturesRawValue) {
        super(id, body, conversationRecipient, individualRecipient, dateSent, dateReceived, threadId, deliveryStatus, deliveryReceiptCount, type, expiresIn, expireStarted, readReceiptCount, reactions, hasMention, messageContent, proFeaturesRawValue);
        this.slideDeck = slideDeck;
        this.quote = quote;
        this.linkPreviews.addAll(linkPreviews);
    }

    @Override
    public boolean isMms() {
        return true;
    }

    @Override
    public boolean isMmsNotification() {
        return false;
    }

    @NonNull
    public SlideDeck getSlideDeck() {
        return slideDeck;
    }

    @Override
    public boolean isMediaPending() {
        for (Slide slide : getSlideDeck().getSlides()) {
            if (slide.isInProgress() || slide.isPendingDownload()) {
                return true;
            }
        }

        return false;
    }

    public boolean containsMediaSlide() {
        return slideDeck.containsMediaSlide();
    }

    public @Nullable Quote getQuote() {
        return quote;
    }

    public @NonNull List<LinkPreview> getLinkPreviews() {
        return linkPreviews;
    }

    public boolean hasAttachmentUri() {
        boolean hasData = false;

        for (Slide slide : slideDeck.getSlides()) {
            if (slide.getUri() != null || slide.getThumbnailUri() != null) {
                hasData = true;
                break;
            }
        }

        return hasData;
    }
}
