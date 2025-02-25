/*
 * Copyright (C) 2012 Moxie Marlinspike
 * Copyright (C) 2013-2017 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
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

import static org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY;
import static org.session.libsession.utilities.StringSubstitutionConstants.AUTHOR_KEY;
import static org.session.libsession.utilities.StringSubstitutionConstants.DISAPPEARING_MESSAGES_TYPE_KEY;
import static org.session.libsession.utilities.StringSubstitutionConstants.MESSAGE_SNIPPET_KEY;
import static org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY;
import static org.session.libsession.utilities.StringSubstitutionConstants.TIME_KEY;

import android.content.Context;
import android.net.Uri;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.squareup.phrase.Phrase;
import org.session.libsession.utilities.TextSecurePreferences;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.database.MmsSmsColumns;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.ui.UtilKt;

import kotlin.Pair;
import network.loki.messenger.R;

/**
 * The message record model which represents thread heading messages.
 *
 * Este archivo se ha modificado para incluir el campo `threadKeyAlias`,
 * que hace referencia a la clave de cifrado específica del hilo (thread).
 */
public class ThreadRecord extends DisplayRecord {

    private @Nullable final Uri snippetUri;
    public  @Nullable final MessageRecord lastMessage;
    private final long   count;
    private final int    unreadCount;
    private final int    unreadMentionCount;
    private final int    distributionType;
    private final boolean archived;
    private final long   expiresIn;
    private final long   lastSeen;
    private final boolean pinned;
    private final int    initialRecipientHash;
    private final long   dateSent;

    // ----------------------------------------------
    // NUEVO: campo para alias de cifrado por hilo
    // ----------------------------------------------
    private final @Nullable String threadKeyAlias;

    /**
     * Constructor principal de ThreadRecord.
     *
     * @param body               El texto principal/snippet del mensaje.
     * @param snippetUri         URI de una imagen de snippet (ej: thumbnail).
     * @param lastMessage        El último MessageRecord en la conversación.
     * @param recipient          El Recipient asociado a este hilo.
     * @param date               Marca de tiempo (ms).
     * @param count              Cantidad de mensajes en el hilo.
     * @param unreadCount        Cantidad de mensajes no leídos.
     * @param unreadMentionCount Cantidad de menciones no leídas.
     * @param threadId           ID del hilo en la BD local.
     * @param deliveryReceiptCount   Conteo de receipts de entrega.
     * @param status             Estado de envío/recepción.
     * @param snippetType        Tipo de snippet (ej: inbound, outbound, etc.).
     * @param distributionType   Tipo de distribución (ej: 1 a 1, grupo, etc.).
     * @param archived           Flag de archivado.
     * @param expiresIn          Tiempo en ms para expirar.
     * @param lastSeen           Última marca de lectura.
     * @param readReceiptCount   Conteo de receipts de lectura.
     * @param pinned             Indica si el hilo está pineado.
     *
     * @param threadKeyAlias     Alias de la clave de cifrado específica del hilo (opcional).
     */
    public ThreadRecord(
            @NonNull String body,
            @Nullable Uri snippetUri,
            @Nullable MessageRecord lastMessage,
            @NonNull Recipient recipient,
            long date,
            long count,
            int unreadCount,
            int unreadMentionCount,
            long threadId,
            int deliveryReceiptCount,
            int status,
            long snippetType,
            int distributionType,
            boolean archived,
            long expiresIn,
            long lastSeen,
            int readReceiptCount,
            boolean pinned,
            @Nullable String threadKeyAlias // nuevo parámetro
    ) {
        super(body, recipient, date, date, threadId, status, deliveryReceiptCount, snippetType, readReceiptCount);
        this.snippetUri           = snippetUri;
        this.lastMessage          = lastMessage;
        this.count                = count;
        this.unreadCount          = unreadCount;
        this.unreadMentionCount   = unreadMentionCount;
        this.distributionType     = distributionType;
        this.archived             = archived;
        this.expiresIn            = expiresIn;
        this.lastSeen             = lastSeen;
        this.pinned               = pinned;
        this.initialRecipientHash = recipient.hashCode();
        this.dateSent             = date;
        this.threadKeyAlias       = threadKeyAlias;
    }

    /**
     * Constructor antiguo sin `threadKeyAlias` para compatibilidad,
     * si fuera necesario no romper implementaciones.
     */
    public ThreadRecord(
            @NonNull String body,
            @Nullable Uri snippetUri,
            @Nullable MessageRecord lastMessage,
            @NonNull Recipient recipient,
            long date,
            long count,
            int unreadCount,
            int unreadMentionCount,
            long threadId,
            int deliveryReceiptCount,
            int status,
            long snippetType,
            int distributionType,
            boolean archived,
            long expiresIn,
            long lastSeen,
            int readReceiptCount,
            boolean pinned
    ) {
        this(
                body,
                snippetUri,
                lastMessage,
                recipient,
                date,
                count,
                unreadCount,
                unreadMentionCount,
                threadId,
                deliveryReceiptCount,
                status,
                snippetType,
                distributionType,
                archived,
                expiresIn,
                lastSeen,
                readReceiptCount,
                pinned,
                null  // threadKeyAlias por defecto = null
        );
    }

    // ----------------------------------------------
    // GETTERS
    // ----------------------------------------------
    public @Nullable Uri getSnippetUri()        { return snippetUri; }
    public @Nullable MessageRecord getLastMessage() { return lastMessage; }
    public long getCount()                     { return count; }
    public int  getUnreadCount()               { return unreadCount; }
    public int  getUnreadMentionCount()        { return unreadMentionCount; }
    public long getDate()                      { return getDateReceived(); }
    public boolean isArchived()                { return archived; }
    public int  getDistributionType()          { return distributionType; }
    public long getExpiresIn()                 { return expiresIn; }
    public long getLastSeen()                  { return lastSeen; }
    public boolean isPinned()                  { return pinned; }
    public int  getInitialRecipientHash()      { return initialRecipientHash; }
    public long getDateSent()                  { return dateSent; }

    // NUEVO: alias cifrado por hilo
    public @Nullable String getThreadKeyAlias() { return threadKeyAlias; }

    private String getName() {
        String name = getRecipient().getName();
        if (name == null) {
            Log.w("ThreadRecord", "Got a null name - using: Unknown");
            name = "Unknown";
        }
        return name;
    }

    @Override
    public CharSequence getDisplayBody(@NonNull Context context) {
        // no need to display anything if there are no messages
        if (lastMessage == null) {
            return "";
        }
        else if (isGroupUpdateMessage()) {
            return context.getString(R.string.groupUpdated);
        } else if (isOpenGroupInvitation()) {
            return context.getString(R.string.communityInvitation);
        } else if (MmsSmsColumns.Types.isLegacyType(type)) {
            return Phrase.from(context, R.string.messageErrorOld)
                    .put(APP_NAME_KEY, context.getString(R.string.app_name))
                    .format().toString();
        } else if (MmsSmsColumns.Types.isDraftMessageType(type)) {
            String draftText = context.getString(R.string.draft);
            return draftText + " " + getBody();
        } else if (SmsDatabase.Types.isOutgoingCall(type)) {
            return Phrase.from(context, R.string.callsYouCalled)
                    .put(NAME_KEY, getName())
                    .format().toString();
        } else if (SmsDatabase.Types.isIncomingCall(type)) {
            return Phrase.from(context, R.string.callsCalledYou)
                    .put(NAME_KEY, getName())
                    .format().toString();
        } else if (SmsDatabase.Types.isMissedCall(type)) {
            return Phrase.from(context, R.string.callsMissedCallFrom)
                    .put(NAME_KEY, getName())
                    .format().toString();
        } else if (SmsDatabase.Types.isExpirationTimerUpdate(type)) {
            if (lastMessage != null) {
                return lastMessage.getDisplayBody(context).toString();
            } else {
                return "";
            }
        } else if (MmsSmsColumns.Types.isMediaSavedExtraction(type)) {
            return Phrase.from(context, R.string.attachmentsMediaSaved)
                    .put(NAME_KEY, getName())
                    .format().toString();

        } else if (MmsSmsColumns.Types.isScreenshotExtraction(type)) {
            return Phrase.from(context, R.string.screenshotTaken)
                    .put(NAME_KEY, getName())
                    .format().toString();

        } else if (MmsSmsColumns.Types.isMessageRequestResponse(type)) {
            try {
                if (lastMessage.getRecipient().getAddress().serialize()
                        .equals(TextSecurePreferences.getLocalNumber(context))) {
                    return UtilKt.getSubbedCharSequence(
                            context,
                            R.string.messageRequestYouHaveAccepted,
                            new Pair<>(NAME_KEY, getName())
                    );
                }
            } catch (Exception e) {
                // the above can throw a null exception
            }
            return context.getString(R.string.messageRequestsAccepted);
        } else if (getCount() == 0) {
            return new SpannableString(context.getString(R.string.messageEmpty));
        } else {
            // If we receive a media message from an unapproved contact, etc...
            if (TextUtils.isEmpty(getBody())) {
                return new SpannableString("");
            } else {
                return getNonControlMessageDisplayBody(context);
            }
        }
    }

    /**
     * Logic to get the body for non-control messages
     */
    public CharSequence getNonControlMessageDisplayBody(@NonNull Context context) {
        Recipient recipient = getRecipient();
        // The logic will differ depending on the type.
        // 1-1, note to self and control messages do not need author details
        if (recipient.isLocalNumber() || recipient.is1on1() ||
                (lastMessage != null && lastMessage.isControlMessage())) {
            return getBody();
        } else {
            // For groups (new, legacy, communities) show either 'You' or the contact's name
            String prefix = "";
            if (lastMessage != null && lastMessage.isOutgoing()) {
                prefix = context.getString(R.string.you);
            } else if (lastMessage != null) {
                prefix = lastMessage.getIndividualRecipient().toShortString();
            }

            return Phrase.from(context.getString(R.string.messageSnippetGroup))
                    .put(AUTHOR_KEY, prefix)
                    .put(MESSAGE_SNIPPET_KEY, getBody())
                    .format()
                    .toString();
        }
    }
}
