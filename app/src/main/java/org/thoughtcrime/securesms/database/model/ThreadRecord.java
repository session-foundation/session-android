/*
 * Copyright (C) 2012 Moxie Marlinspike
 * Copyright (C) 2013-2017 Open Whisper Systems
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

import static org.session.libsession.utilities.StringSubstitutionConstants.AUTHOR_KEY;
import static org.session.libsession.utilities.StringSubstitutionConstants.MESSAGE_SNIPPET_KEY;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.squareup.phrase.Phrase;

import org.session.libsession.utilities.AddressKt;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsession.utilities.recipients.RecipientNamesKt;
import org.thoughtcrime.securesms.database.model.content.MessageContent;

import network.loki.messenger.R;

/**
 * The message record model which represents thread heading messages.
 *
 * @author Moxie Marlinspike
 *
 */
public class ThreadRecord extends DisplayRecord {
    public @Nullable  final MessageRecord lastMessage;
    private           final long    count;
    private           final int     unreadCount;
    private           final int     unreadMentionCount;
    private           final long    lastSeen;
    private           final String invitingAdminId;
    private           final boolean isUnread;

    @NonNull
    public            final GroupThreadStatus groupThreadStatus;

    public ThreadRecord(@NonNull String body,
                        @Nullable MessageRecord lastMessage, @NonNull Recipient recipient, long date, long count, int unreadCount,
                        int unreadMentionCount, long threadId, int deliveryReceiptCount, int status,
                        long snippetType,
                        long lastSeen, int readReceiptCount, String invitingAdminId,
                        @NonNull GroupThreadStatus groupThreadStatus,
                        @Nullable MessageContent messageContent,
                        boolean isUnread)
    {
        super(body, recipient, date, date, threadId, status, deliveryReceiptCount, snippetType, readReceiptCount, messageContent);
        this.lastMessage        = lastMessage;
        this.count              = count;
        this.unreadCount        = unreadCount;
        this.unreadMentionCount = unreadMentionCount;
        this.lastSeen           = lastSeen;
        this.invitingAdminId    = invitingAdminId;
        this.groupThreadStatus  = groupThreadStatus;
        this.isUnread = isUnread;
    }


    public long getCount()               { return count; }

    public int getUnreadCount()          { return unreadCount; }

    public int getUnreadMentionCount()   { return unreadMentionCount; }

    public long getDate()                { return getDateReceived(); }

    public long getLastSeen()            { return lastSeen; }

    public boolean isPinned()            { return getRecipient().isPinned(); }

    public String getInvitingAdminId() {
        return invitingAdminId;
    }

    public boolean isUnread() {
        return isUnread;
    }
}
