/*
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

import androidx.annotation.Nullable;

import org.session.libsession.utilities.recipients.Recipient;

import java.util.List;
import java.util.Set;

import network.loki.messenger.libsession_util.protocol.ProFeature;

/**
 * The message record model which represents standard SMS messages.
 *
 * @author Moxie Marlinspike
 *
 */
public class SmsMessageRecord extends MessageRecord {

    public SmsMessageRecord(long id,
                            String body, Recipient recipient,
                            Recipient individualRecipient,
                            long dateSent, long dateReceived,
                            int deliveryReceiptCount,
                            long type, long threadId,
                            int status,
                            long expiresIn, long expireStarted,
                            int readReceiptCount, List<ReactionRecord> reactions, boolean hasMention,
                            Set<ProFeature> proFeatures,
                            @Nullable String serverHash) {
        super(id, body, recipient, individualRecipient,
                dateSent, dateReceived, threadId, status, deliveryReceiptCount, type,
                expiresIn, expireStarted, readReceiptCount, reactions, hasMention, null,
                proFeatures, serverHash);
    }

    public long getType() {
        return type;
    }


    @Override
    public boolean isMms() {
        return false;
    }

    @Override
    public boolean isMmsNotification() {
        return false;
    }
}
