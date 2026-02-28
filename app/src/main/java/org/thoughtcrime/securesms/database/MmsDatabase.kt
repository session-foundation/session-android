/**
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
 * along with this program.  If not, see <http:></http:>//www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.collection.MutableLongObjectMap
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.session.libsession.messaging.messages.signal.IncomingMediaMessage
import org.session.libsession.messaging.messages.signal.OutgoingMediaMessage
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsession.utilities.isGroupOrCommunity
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.toGroupString
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.ThreadUtils.queue
import org.thoughtcrime.securesms.database.MmsDatabase.Companion.MESSAGE_BOX
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.Quote
import org.thoughtcrime.securesms.database.model.content.DisappearingMessageUpdate
import org.thoughtcrime.securesms.database.model.content.MessageContent
import org.thoughtcrime.securesms.mms.MmsException
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.preferences.CommunicationPreferences
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.pro.toProMessageBitSetValue
import org.thoughtcrime.securesms.pro.toProMessageFeatures
import org.thoughtcrime.securesms.pro.toProProfileBitSetValue
import org.thoughtcrime.securesms.pro.toProProfileFeatures
import org.thoughtcrime.securesms.util.asSequence
import java.io.Closeable
import java.util.LinkedList
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class MmsDatabase @Inject constructor(
    @ApplicationContext context: Context,
    databaseHelper: Provider<SQLCipherOpenHelper>,
    private val recipientRepository: RecipientRepository,
    private val json: Json,
    private val groupReceiptDatabase: GroupReceiptDatabase,
    private val attachmentDatabase: AttachmentDatabase,
    private val reactionDatabase: ReactionDatabase,
    private val mmsSmsDatabase: Lazy<MmsSmsDatabase>,
    private val groupDatabase: GroupDatabase,
    private val snodeClock: SnodeClock,
    private val prefs: Provider<PreferenceStorage>,
) : MessagingDatabase(context, databaseHelper) {
    private val earlyDeliveryReceiptCache = EarlyReceiptCache()
    private val earlyReadReceiptCache = EarlyReceiptCache()
    override fun getTableName() = TABLE_NAME

    private val _changeNotification = MutableSharedFlow<MessageUpdateNotification>(extraBufferCapacity = 24)

    val changeNotification: SharedFlow<MessageUpdateNotification> get() = _changeNotification

    fun getMessageCountForThread(threadId: Long): Int {
        val db = readableDatabase
        db.query(
            TABLE_NAME,
            arrayOf("COUNT(*)"),
            "$THREAD_ID = ?",
            arrayOf(threadId.toString()),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return 0
    }

    fun isOutgoingMessage(id: Long): Boolean =
        readableDatabase.query(
            TABLE_NAME,
            arrayOf(ID, THREAD_ID, MESSAGE_BOX, ADDRESS),
            "$ID = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            null
        ).use { cursor ->
            cursor.asSequence()
                .map { cursor.getColumnIndexOrThrow(MESSAGE_BOX) }
                .map(cursor::getLong)
                .any { MmsSmsColumns.Types.isOutgoingMessageType(it) }
        }

    fun getOutgoingMessageProFeatureCount(featureMask: Long): Int {
        return getOutgoingProFeatureCountInternal(PRO_MESSAGE_FEATURES, featureMask)
    }

    fun getOutgoingProfileProFeatureCount(featureMask: Long): Int {
        return getOutgoingProFeatureCountInternal(PRO_PROFILE_FEATURES, featureMask)
    }

    private fun getOutgoingProFeatureCountInternal(column: String, featureMask: Long): Int {
        val db = readableDatabase
        val where = "($column & $featureMask) != 0 AND $IS_OUTGOING"

        db.query(TABLE_NAME, arrayOf("COUNT(*)"), where, null, null, null, null).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0)
            }
        }
        return 0
    }

    fun isDeletedMessage(id: Long): Boolean =
        readableDatabase.query(
            TABLE_NAME,
            arrayOf(ID, THREAD_ID, MESSAGE_BOX, ADDRESS),
            "$ID = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            null
        ).use { cursor ->
            cursor.asSequence()
                .map { cursor.getColumnIndexOrThrow(MESSAGE_BOX) }
                .map(cursor::getLong)
                .any { MmsSmsColumns.Types.isDeletedMessage(it) }
        }

    fun incrementReceiptCount(
        messageId: SyncMessageId,
        timestamp: Long,
        deliveryReceipt: Boolean,
        readReceipt: Boolean
    ) {
        val database = writableDatabase
        var cursor: Cursor? = null
        var found = false
        try {
            cursor = database.query(
                TABLE_NAME,
                arrayOf(ID, THREAD_ID, MESSAGE_BOX, ADDRESS),
                "$DATE_SENT = ?",
                arrayOf(messageId.timetamp.toString()),
                null,
                null,
                null,
                null
            )
            while (cursor.moveToNext()) {
                if (MmsSmsColumns.Types.isOutgoingMessageType(
                        cursor.getLong(
                            cursor.getColumnIndexOrThrow(
                                MESSAGE_BOX
                            )
                        )
                    )
                ) {
                    val theirAddress = fromSerialized(
                        cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                ADDRESS
                            )
                        )
                    )
                    val ourAddress = messageId.address
                    val columnName =
                        if (deliveryReceipt) DELIVERY_RECEIPT_COUNT else READ_RECEIPT_COUNT
                    if (ourAddress.equals(theirAddress) || theirAddress.isGroupOrCommunity) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(ID))
                        val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(THREAD_ID))
                        val status =
                            if (deliveryReceipt) GroupReceiptDatabase.STATUS_DELIVERED else GroupReceiptDatabase.STATUS_READ
                        found = true
                        database.execSQL(
                            "UPDATE $TABLE_NAME SET $columnName = $columnName + 1 WHERE $ID = ?",
                            arrayOf(id)
                        )
                        groupReceiptDatabase
                            .update(ourAddress, id, status, timestamp)

                        _changeNotification.tryEmit(MessageUpdateNotification(
                            changeType = MessageUpdateNotification.ChangeType.Updated,
                            id = MessageId(id, true),
                            threadId = threadId
                        ))
                    }
                }
            }
            if (!found) {
                if (deliveryReceipt) earlyDeliveryReceiptCache.increment(
                    messageId.timetamp,
                    messageId.address
                )
                if (readReceipt) earlyReadReceiptCache.increment(
                    messageId.timetamp,
                    messageId.address
                )
            }
        } finally {
            cursor?.close()
        }
    }

    fun updateSentTimestamp(messageId: Long, newTimestamp: Long) {
        //language=roomsql
        writableDatabase.query("""
            UPDATE $TABLE_NAME 
            SET $DATE_SENT = ?1 
            WHERE $ID = ?2 AND IFNULL($DATE_SENT, 0) != ?1 
            RETURNING $THREAD_ID""",
            arrayOf(newTimestamp, messageId)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val threadId = cursor.getLong(0)
                _changeNotification.tryEmit(MessageUpdateNotification(
                    changeType = MessageUpdateNotification.ChangeType.Updated,
                    id = MessageId(messageId, true),
                    threadId = threadId
                ))
            }
        }
    }

    fun getThreadIdForMessage(id: Long): Long {
        val sql = "SELECT $THREAD_ID FROM $TABLE_NAME WHERE $ID = ?"
        val sqlArgs = arrayOf(id.toString())
        val db = readableDatabase
        var cursor: Cursor? = null
        return try {
            cursor = db.rawQuery(sql, sqlArgs)
            if (cursor != null && cursor.moveToFirst()) cursor.getLong(0) else -1
        } finally {
            cursor?.close()
        }
    }

    override fun getExpiredMessageIDs(nowMills: Long): List<Long> {
        val query = "SELECT " + ID + " FROM " + TABLE_NAME +
                " WHERE " + EXPIRES_IN + " > 0 AND " + EXPIRE_STARTED + " > 0 AND " + EXPIRE_STARTED + " + " + EXPIRES_IN + " <= ?"

        return readableDatabase.rawQuery(query, nowMills).use { cursor ->
            cursor.asSequence()
                .map { it.getLong(0) }
                .toList()
        }
    }

    /**
     * @return the next expiring timestamp for messages that have started expiring. 0 if no messages are expiring.
     */
    override fun getNextExpiringTimestamp(): Long {
        val query =
            "SELECT MIN(" + EXPIRE_STARTED + " + " + EXPIRES_IN + ") FROM " + TABLE_NAME +
                    " WHERE " + EXPIRES_IN + " > 0 AND " + EXPIRE_STARTED + " > 0"

        return readableDatabase.rawQuery(query).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(0)
            } else {
                0L
            }
        }
    }

    private fun markAs(
        messageId: Long,
        baseType: Long
    ) {
        //language=roomsql
        this.writableDatabase.query(
            """
            UPDATE $TABLE_NAME 
            SET $MESSAGE_BOX = (${MESSAGE_BOX} & ${MmsSmsColumns.Types.TOTAL_MASK - MmsSmsColumns.Types.BASE_TYPE_MASK} | $baseType) 
            WHERE $ID = ?
            RETURNING $THREAD_ID""",
            arrayOf(messageId)
        ).use { cursor ->
            if (cursor.moveToNext()) {
                _changeNotification.tryEmit(
                    MessageUpdateNotification(
                        changeType = MessageUpdateNotification.ChangeType.Updated,
                        id = MessageId(messageId, true),
                        threadId = cursor.getLong(0)
                    )
                )
            }
        }
    }

    override fun markAsSyncing(messageId: Long) {
        markAs(messageId, MmsSmsColumns.Types.BASE_SYNCING_TYPE)
    }
    override fun markAsResyncing(messageId: Long) {
        markAs(messageId, MmsSmsColumns.Types.BASE_RESYNCING_TYPE)
    }
    override fun markAsSyncFailed(messageId: Long) {
        markAs(messageId, MmsSmsColumns.Types.BASE_SYNC_FAILED_TYPE)
    }

    fun markAsSending(messageId: Long) {
        markAs(messageId, MmsSmsColumns.Types.BASE_SENDING_TYPE)
    }

    fun markAsSentFailed(messageId: Long) {
        markAs(messageId, MmsSmsColumns.Types.BASE_SENT_FAILED_TYPE)
    }

    override fun markAsSent(messageId: Long, isSent: Boolean) {
        markAs(
            messageId,
            MmsSmsColumns.Types.BASE_SENT_TYPE or if (isSent) MmsSmsColumns.Types.PUSH_MESSAGE_BIT or MmsSmsColumns.Types.SECURE_MESSAGE_BIT else 0
        )
    }

    override fun markAsDeleted(messageId: Long, isOutgoing: Boolean, displayedMessage: String) {
        val database = writableDatabase
        val contentValues = ContentValues()
        contentValues.put(READ, 1)
        contentValues.put(BODY, displayedMessage)
        contentValues.put(HAS_MENTION, 0)

        database.update(TABLE_NAME, contentValues, ID_WHERE, arrayOf(messageId.toString()))
        queue { attachmentDatabase.deleteAttachmentsForMessage(messageId) }
        val deletedType = if (isOutgoing) {  MmsSmsColumns.Types.BASE_DELETED_OUTGOING_TYPE} else {
            MmsSmsColumns.Types.BASE_DELETED_INCOMING_TYPE
        }

        // We rely on the markAs to notify the change so we don't have to do it ourselves
        markAs(messageId, deletedType)
    }

    override fun markExpireStarted(messageId: Long, startedTimestamp: Long) {
        //language=roomsql
        writableDatabase.rawQuery("""
            UPDATE $TABLE_NAME SET $EXPIRE_STARTED = ?1
            WHERE $ID = ?2 AND IFNULL($EXPIRE_STARTED, 0) != ?1  
            RETURNING $THREAD_ID
        """, startedTimestamp, messageId).use { cursor ->
            if (cursor.moveToNext()) {
                _changeNotification.tryEmit(
                    MessageUpdateNotification(
                        changeType = MessageUpdateNotification.ChangeType.Updated,
                        id = MessageId(messageId, true),
                        threadId = cursor.getLong(0)
                    )
                )
            }
        }
    }

    private fun getLinkPreviews(
        cursor: Cursor,
        attachments: List<DatabaseAttachment>
    ): List<LinkPreview> {
        val serializedPreviews = cursor.getString(cursor.getColumnIndexOrThrow(LINK_PREVIEWS))
        if (serializedPreviews.isNullOrEmpty()) {
            return emptyList()
        }
        val attachmentIdMap: MutableMap<AttachmentId?, DatabaseAttachment> = HashMap()
        for (attachment in attachments) {
            attachmentIdMap[attachment.attachmentId] = attachment
        }

        return runCatching {
            json.decodeFromString<List<LinkPreview>>(serializedPreviews)
                .mapNotNull { preview ->
                    if (preview.attachmentId != null) {
                        attachmentIdMap[preview.attachmentId]?.let { attachment ->
                            preview.copy(thumbnail = attachment)
                        }
                    } else {
                        preview
                    }
                }
        }.onFailure { err ->
            Log.w(TAG, "Failed to decode link preview", err)
        }.getOrNull()
            .orEmpty()
    }

    private fun insertMessageInbox(
        retrieved: IncomingMediaMessage,
        threadId: Long,
        mailbox: Long,
        serverTimestamp: Long
    ): InsertResult? {
        if (threadId < 0 ) throw MmsException("No thread ID supplied!")
        if (retrieved.messageContent is DisappearingMessageUpdate)
            deleteExpirationTimerMessages(threadId, false.takeUnless { retrieved.group != null })
        val contentValues = ContentValues()
        contentValues.put(DATE_SENT, retrieved.sentTimeMillis)
        contentValues.put(ADDRESS, retrieved.from.toString())
        contentValues.put(MESSAGE_BOX, mailbox)
        contentValues.put(THREAD_ID, threadId)
        contentValues.put(STATUS, Status.DOWNLOAD_INITIALIZED)
        contentValues.put(PRO_MESSAGE_FEATURES, retrieved.proFeatures.toProMessageBitSetValue())
        contentValues.put(PRO_PROFILE_FEATURES, retrieved.proFeatures.toProProfileBitSetValue())
        // In open groups messages should be sorted by their server timestamp
        var receivedTimestamp = serverTimestamp
        if (serverTimestamp == 0L) {
            receivedTimestamp = retrieved.sentTimeMillis
        }
        contentValues.put(DATE_RECEIVED, receivedTimestamp) // Loki - This is important due to how we handle GIFs
        contentValues.put(EXPIRES_IN, retrieved.expiresIn)
        contentValues.put(EXPIRE_STARTED, retrieved.expireStartedAt)
        contentValues.put(HAS_MENTION, retrieved.hasMention)
        contentValues.put(MESSAGE_REQUEST_RESPONSE, retrieved.isMessageRequestResponse)
        if (!contentValues.containsKey(DATE_SENT)) {
            contentValues.put(DATE_SENT, contentValues.getAsLong(DATE_RECEIVED))
        }
        var quoteAttachments: List<Attachment?>? = LinkedList()
        if (retrieved.quote != null) {
            contentValues.put(QUOTE_ID, retrieved.quote.id)
            contentValues.put(QUOTE_AUTHOR, retrieved.quote.author.toString())
            contentValues.put(QUOTE_MISSING, if (retrieved.quote.missing) 1 else 0)
            quoteAttachments = retrieved.quote.attachments
        }
        if (isDuplicate(retrieved, threadId) ||
            retrieved.isMessageRequestResponse && isDuplicateMessageRequestResponse(
                retrieved,
                threadId
            )
        ) {
            Log.w(TAG, "Ignoring duplicate media message (" + retrieved.sentTimeMillis + ")")
            return null
        }
        val messageId = insertMediaMessage(
            body = retrieved.body,
            messageContent = retrieved.messageContent,
            attachments = retrieved.attachments,
            quoteAttachments = quoteAttachments!!,
            linkPreviews = retrieved.linkPreviews,
            contentValues = contentValues,
        )

        _changeNotification.tryEmit(MessageUpdateNotification(
            changeType = MessageUpdateNotification.ChangeType.Added,
            id = MessageId(messageId, true),
            threadId = contentValues.getAsLong(THREAD_ID)
        ))

        return InsertResult(messageId, threadId)
    }

    @Throws(MmsException::class)
    fun insertSecureDecryptedMessageOutbox(
        retrieved: OutgoingMediaMessage,
        threadId: Long,
        serverTimestamp: Long
    ): InsertResult? {
        if (threadId < 0 ) throw MmsException("No thread ID supplied!")
        if (retrieved.messageContent is DisappearingMessageUpdate) deleteExpirationTimerMessages(threadId, true.takeUnless { retrieved.isGroup })
        val messageId = insertMessageOutbox(
            retrieved,
            threadId,
            false,
            serverTimestamp
        )
        if (messageId == -1L) {
            Log.w(TAG, "insertSecureDecryptedMessageOutbox believes the MmsDatabase insertion failed.")
            return null
        }
        markAsSent(messageId, true)
        return InsertResult(messageId, threadId)
    }

    @JvmOverloads
    @Throws(MmsException::class)
    fun insertSecureDecryptedMessageInbox(
        retrieved: IncomingMediaMessage,
        threadId: Long,
        serverTimestamp: Long = 0
    ): InsertResult? {
        var type = MmsSmsColumns.Types.BASE_INBOX_TYPE or MmsSmsColumns.Types.SECURE_MESSAGE_BIT or MmsSmsColumns.Types.PUSH_MESSAGE_BIT
        if (retrieved.isMediaSavedDataExtraction) {
            type = type or MmsSmsColumns.Types.MEDIA_SAVED_EXTRACTION_BIT
        }
        if (retrieved.isMessageRequestResponse) {
            type = type or MmsSmsColumns.Types.MESSAGE_REQUEST_RESPONSE_BIT
        }
        return insertMessageInbox(retrieved, threadId, type, serverTimestamp)
    }

    @Throws(MmsException::class)
    fun insertMessageOutbox(
        message: OutgoingMediaMessage,
        threadId: Long,
        forceSms: Boolean,
        serverTimestamp: Long = 0
    ): Long {
        var type = MmsSmsColumns.Types.BASE_SENDING_TYPE
        if (message.isSecure) type =
            type or (MmsSmsColumns.Types.SECURE_MESSAGE_BIT or MmsSmsColumns.Types.PUSH_MESSAGE_BIT)
        if (forceSms) type = type or MmsSmsColumns.Types.MESSAGE_FORCE_SMS_BIT
        if (message.isGroup) {
            if (message.isGroupUpdateMessage) type = type or MmsSmsColumns.Types.GROUP_UPDATE_MESSAGE_BIT
        }
        val earlyDeliveryReceipts = earlyDeliveryReceiptCache.remove(message.sentTimeMillis)
        val earlyReadReceipts = earlyReadReceiptCache.remove(message.sentTimeMillis)
        val contentValues = ContentValues()
        contentValues.put(DATE_SENT, message.sentTimeMillis)
        contentValues.put(MESSAGE_BOX, type)
        contentValues.put(THREAD_ID, threadId)
        contentValues.put(READ, 1)
        // In open groups messages should be sorted by their server timestamp
        var receivedTimestamp = serverTimestamp
        if (serverTimestamp == 0L) {
            receivedTimestamp = snodeClock.currentTimeMillis()
        }
        contentValues.put(DATE_RECEIVED, receivedTimestamp)
        contentValues.put(EXPIRES_IN, message.expiresInMillis)
        contentValues.put(EXPIRE_STARTED, message.expireStartedAtMillis)
        contentValues.put(ADDRESS, message.recipient.toString())
        contentValues.put(PRO_PROFILE_FEATURES, message.proFeatures.toProProfileBitSetValue())
        contentValues.put(PRO_MESSAGE_FEATURES, message.proFeatures.toProMessageBitSetValue())
        contentValues.put(DELIVERY_RECEIPT_COUNT, earlyDeliveryReceipts.values.sum())
        contentValues.put(READ_RECEIPT_COUNT, earlyReadReceipts.values.sum())
        val quoteAttachments: MutableList<Attachment?> = LinkedList()
        if (message.outgoingQuote != null) {
            contentValues.put(QUOTE_ID, message.outgoingQuote.id)
            contentValues.put(QUOTE_AUTHOR, message.outgoingQuote.author.toString())
            contentValues.put(QUOTE_MISSING, if (message.outgoingQuote.missing) 1 else 0)
            quoteAttachments.addAll(message.outgoingQuote.attachments!!)
        }
        if (isDuplicate(message, threadId)) {
            Log.w(TAG, "Ignoring duplicate media message (" + message.sentTimeMillis + ")")
            return -1
        }
        val messageId = insertMediaMessage(
            body = message.body,
            messageContent = message.messageContent,
            attachments = message.attachments,
            quoteAttachments = quoteAttachments,
            linkPreviews = message.linkPreviews,
            contentValues = contentValues,
        )
        if (message.recipient.isGroupOrCommunity) {
            val members = groupDatabase
                .getGroupMembers(message.recipient.toGroupString(), false)
            groupReceiptDatabase.insert(members,
                messageId, GroupReceiptDatabase.STATUS_UNDELIVERED, message.sentTimeMillis
            )
            for (address in earlyDeliveryReceipts.keys) groupReceiptDatabase.update(
                address,
                messageId,
                GroupReceiptDatabase.STATUS_DELIVERED,
                -1
            )
            for (address in earlyReadReceipts.keys) groupReceiptDatabase.update(
                address,
                messageId,
                GroupReceiptDatabase.STATUS_READ,
                -1
            )
        }

        _changeNotification.tryEmit(MessageUpdateNotification(
            changeType = MessageUpdateNotification.ChangeType.Added,
            id = MessageId(messageId, true),
            threadId = threadId
        ))

        return messageId
    }

    private fun insertMediaMessage(
        body: String?,
        messageContent: MessageContent?,
        attachments: List<Attachment?>,
        quoteAttachments: List<Attachment?>,
        linkPreviews: List<LinkPreview>,
        contentValues: ContentValues,
    ): Long {
        val db = writableDatabase
        val partsDatabase = attachmentDatabase
        val allAttachments: MutableList<Attachment?> = LinkedList()
        val thumbnailJobs: MutableList<AttachmentId> = ArrayList()  // Collector for thumbnail jobs

        val previewAttachments: List<Attachment> =
            linkPreviews
                .mapNotNull { lp -> lp.thumbnail }

        allAttachments.addAll(attachments)
        allAttachments.addAll(previewAttachments)

        contentValues.put(BODY, body)
        contentValues.put(PART_COUNT, allAttachments.size)
        contentValues.put(MESSAGE_CONTENT, messageContent?.let { json.encodeToString(it) })

        db.beginTransaction()
        return try {
            val messageId = db.insert(TABLE_NAME, null, contentValues)

            // Pass thumbnailJobs collector to attachment insertion
            val insertedAttachments = partsDatabase.insertAttachmentsForMessage(
                messageId,
                allAttachments,
                quoteAttachments,
                thumbnailJobs  // This will collect all attachment IDs that need thumbnails
            )

            val serializedPreviews = getSerializedLinkPreviews(insertedAttachments, linkPreviews)

            if (!serializedPreviews.isNullOrEmpty()) {
                val contactValues = ContentValues()
                contactValues.put(LINK_PREVIEWS, serializedPreviews)
                val database = readableDatabase
                val rows = database.update(
                    TABLE_NAME,
                    contactValues,
                    "$ID = ?",
                    arrayOf(messageId.toString())
                )
                if (rows <= 0) {
                    Log.w(TAG, "Failed to update message with link preview data.")
                }
            }

            db.setTransactionSuccessful()
            messageId
        } finally {
            db.endTransaction()

            // Process thumbnail jobs AFTER transaction commits
            thumbnailJobs.forEach { attachmentId ->
                Log.i(TAG, "Submitting thumbnail generation job for attachment: $attachmentId")
                attachmentDatabase.thumbnailExecutor.submit(
                    attachmentDatabase.ThumbnailFetchCallable(attachmentId)
                )
            }
        }
    }

    private fun doDeleteMessages(where: String, vararg whereArgs: Any?): Boolean {
        val deleted = mutableListOf<Long>()
        val deletedByThreadIDs: MutableLongObjectMap<ArrayList<MessageId>>

        //language=roomsql
        writableDatabase.rawQuery(
            "DELETE FROM $TABLE_NAME WHERE $where RETURNING $ID, $THREAD_ID",
            *whereArgs
        ).use { cursor ->
            deletedByThreadIDs = MutableLongObjectMap()

            while (cursor.moveToNext()) {
                val threadId = cursor.getLong(1)
                val messageId = MessageId(cursor.getLong(0), true)

                deletedByThreadIDs.getOrPut(threadId) { ArrayList() } += messageId
                deleted += messageId.id
            }
        }

        // Delete messages related data from other tables
        if (deletedByThreadIDs.isNotEmpty()) {
            attachmentDatabase.deleteAttachmentsForMessages(deleted)
            groupReceiptDatabase.deleteRowsForMessages(deleted)

            notifyStickerListeners()
            notifyStickerPackListeners()
        }

        deletedByThreadIDs.forEach { threadId, deletedMessageIDs ->
            _changeNotification.tryEmit(MessageUpdateNotification(
                changeType = MessageUpdateNotification.ChangeType.Deleted,
                ids = deletedMessageIDs,
                threadId = threadId
            ))
        }

        return deleted.isNotEmpty()
    }

    override fun getTypeColumn(): String = MESSAGE_BOX

    override fun deleteMessage(messageId: Long) {
        doDeleteMessages(
            where = "$ID = ?",
            messageId
        )
    }

    override fun deleteMessages(messageIds: Collection<Long>) {
        doDeleteMessages(
            where = "$ID IN (SELECT value FROM json_each(?))",
            JSONArray(messageIds).toString()
        )
    }

    override fun updateThreadId(fromId: Long, toId: Long) {
        if (fromId == toId) {
            return
        }

        //language=roomsql
        val updatedMessageIDs = writableDatabase.query("""
            UPDATE $TABLE_NAME
            SET $THREAD_ID = ?1
            WHERE $THREAD_ID = ?2
            RETURNING $ID
        """, arrayOf(toId, fromId)).use { cursor ->
            cursor.asSequence()
                .map { MessageId(it.getLong(0), true) }
                .toList()
        }

        _changeNotification.tryEmit(
            MessageUpdateNotification(
                changeType = MessageUpdateNotification.ChangeType.Deleted,
                ids = updatedMessageIDs,
                threadId = fromId
            )
        )

        _changeNotification.tryEmit(
            MessageUpdateNotification(
                changeType = MessageUpdateNotification.ChangeType.Added,
                ids = updatedMessageIDs,
                threadId = toId
            )
        )
    }

    fun deleteThread(threadId: Long, updateThread: Boolean) {
        deleteThreads(listOf(threadId), updateThread)
    }

    fun deleteMediaFor(threadId: Long, fromUser: String? = null) {
        if (fromUser != null) {
            doDeleteMessages(
                where = "$THREAD_ID = ? AND $ADDRESS = ? AND $LINK_PREVIEWS IS NULL",
                threadId, fromUser
            )
        } else {
            doDeleteMessages(
                where = "$THREAD_ID = ? AND $LINK_PREVIEWS IS NULL",
                threadId
            )
        }
    }

    fun deleteMessagesFrom(threadId: Long, fromUser: String) { // copied from deleteThreads implementation
        doDeleteMessages(
            where = "$THREAD_ID = ? AND $ADDRESS = ?",
            threadId, fromUser
        )
    }


    private fun getSerializedLinkPreviews(
        insertedAttachmentIds: Map<Attachment?, AttachmentId?>,
        previews: List<LinkPreview?>
    ): String? {
        if (previews.isEmpty()) return null
        val normalisedPreviews = arrayListOf<LinkPreview>()
        for (preview in previews) {
            var attachmentId: AttachmentId? = null
            val thumb = preview!!.thumbnail
            if (thumb != null) {
                attachmentId = insertedAttachmentIds[thumb]
            }

            normalisedPreviews += LinkPreview(
                preview.url, preview.title, attachmentId
            )
        }
        return json.encodeToString(normalisedPreviews)
    }

    private fun isDuplicateMessageRequestResponse(
        message: IncomingMediaMessage?,
        threadId: Long
    ): Boolean {
        val database = readableDatabase
        val cursor: Cursor? = database!!.query(
            TABLE_NAME,
            null,
            MESSAGE_REQUEST_RESPONSE + " = 1 AND " + ADDRESS + " = ? AND " + THREAD_ID + " = ?",
            arrayOf<String?>(
                message!!.from.toString(), threadId.toString()
            ),
            null,
            null,
            null,
            "1"
        )
        return try {
            cursor != null && cursor.moveToFirst()
        } finally {
            cursor?.close()
        }
    }

    private fun isDuplicate(message: IncomingMediaMessage?, threadId: Long): Boolean {
        val database = readableDatabase
        val cursor: Cursor? = database!!.query(
            TABLE_NAME,
            null,
            DATE_SENT + " = ? AND " + ADDRESS + " = ? AND " + THREAD_ID + " = ?",
            arrayOf<String?>(
                message!!.sentTimeMillis.toString(), message.from.toString(), threadId.toString()
            ),
            null,
            null,
            null,
            "1"
        )
        return try {
            cursor != null && cursor.moveToFirst()
        } finally {
            cursor?.close()
        }
    }

    private fun isDuplicate(message: OutgoingMediaMessage?, threadId: Long): Boolean {
        val database = readableDatabase
        val cursor: Cursor? = database!!.query(
            TABLE_NAME,
            null,
            DATE_SENT + " = ? AND " + ADDRESS + " = ? AND " + THREAD_ID + " = ?",
            arrayOf<String?>(
                message!!.sentTimeMillis.toString(),
                message.recipient.address.toString(),
                threadId.toString()
            ),
            null,
            null,
            null,
            "1"
        )
        return try {
            cursor != null && cursor.moveToFirst()
        } finally {
            cursor?.close()
        }
    }

    fun isSent(messageId: Long): Boolean {
        val database = readableDatabase
        database!!.query(
            TABLE_NAME,
            arrayOf(MESSAGE_BOX),
            "$ID = ?",
            arrayOf<String?>(messageId.toString()),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor != null && cursor.moveToNext()) {
                val type = cursor.getLong(cursor.getColumnIndexOrThrow(MESSAGE_BOX))
                return MmsSmsColumns.Types.isSentType(type)
            }
        }
        return false
    }

    fun deleteThreads(threadIds: Collection<Long>, updateThread: Boolean) {
        doDeleteMessages(
            where = "$THREAD_ID IN (SELECT value FROM json_each(?))",
            JSONArray(threadIds).toString()
        )
    }

    /*package*/
    fun deleteMessagesInThreadBeforeDate(threadId: Long, date: Long, onlyMedia: Boolean) {
        var where =
            THREAD_ID + " = ? AND (CASE (" + MESSAGE_BOX + " & " + MmsSmsColumns.Types.BASE_TYPE_MASK + ") "

        for (outgoingType in MmsSmsColumns.Types.OUTGOING_MESSAGE_TYPES) {
            where += " WHEN $outgoingType THEN $DATE_SENT < $date"
        }

        where += " ELSE $DATE_RECEIVED < $date END)"
        if (onlyMedia) where += " AND $PART_COUNT >= 1"

        doDeleteMessages(
            where = where,
            threadId
        )
    }

    fun readerFor(cursor: Cursor?, getQuote: Boolean = true) = Reader(cursor, getQuote)

    /**
     * @param outgoing if true only delete outgoing messages, if false only delete incoming messages, if null delete both.
     */
    private fun deleteExpirationTimerMessages(threadId: Long, outgoing: Boolean? = null) {
        val outgoingClause = when (outgoing) {
            null -> ""
            true -> " AND $IS_OUTGOING"
            false -> " AND NOT $IS_OUTGOING"
        }

        val where = "$THREAD_ID = ? AND $MESSAGE_CONTENT->>'$.${MessageContent.DISCRIMINATOR}' == '${DisappearingMessageUpdate.TYPE_NAME}' " + outgoingClause

        doDeleteMessages(where, threadId)
    }

    object Status {
        const val DOWNLOAD_INITIALIZED = 1
        const val DOWNLOAD_NO_CONNECTIVITY = 2
        const val DOWNLOAD_CONNECTING = 3
    }

    inner class Reader(private val cursor: Cursor?, private val getQuote: Boolean = true) : Closeable {
        val next: MessageRecord?
            get() = if (cursor == null || !cursor.moveToNext()) null else current
        val current: MessageRecord
            get() {
                return getMediaMmsMessageRecord(cursor!!, getQuote)
            }

        private fun getMediaMmsMessageRecord(cursor: Cursor, getQuote: Boolean): MmsMessageRecord {
            val id                   = cursor.getLong(cursor.getColumnIndexOrThrow(ID))
            val dateSent             = cursor.getLong(cursor.getColumnIndexOrThrow(NORMALIZED_DATE_SENT))
            val dateReceived         = cursor.getLong(cursor.getColumnIndexOrThrow(NORMALIZED_DATE_RECEIVED))
            val box                  = cursor.getLong(cursor.getColumnIndexOrThrow(MESSAGE_BOX))
            val threadId             = cursor.getLong(cursor.getColumnIndexOrThrow(THREAD_ID))
            val address              = cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS))
            val deliveryReceiptCount = cursor.getInt(cursor.getColumnIndexOrThrow(DELIVERY_RECEIPT_COUNT))
            var readReceiptCount     = cursor.getInt(cursor.getColumnIndexOrThrow(READ_RECEIPT_COUNT))
            val body                 = cursor.getString(cursor.getColumnIndexOrThrow(BODY))
            val expiresIn            = cursor.getLong(cursor.getColumnIndexOrThrow(EXPIRES_IN))
            val expireStarted        = cursor.getLong(cursor.getColumnIndexOrThrow(EXPIRE_STARTED))
            val hasMention           = cursor.getInt(cursor.getColumnIndexOrThrow(HAS_MENTION)) == 1
            val messageContentJson   = cursor.getString(cursor.getColumnIndexOrThrow(MESSAGE_CONTENT))

            val proFeatures = buildSet {
                cursor.getLong(cursor.getColumnIndexOrThrow(PRO_MESSAGE_FEATURES)).toProMessageFeatures(this)
                cursor.getLong(cursor.getColumnIndexOrThrow(PRO_PROFILE_FEATURES)).toProProfileFeatures(this)
            }

            if (!prefs.get()[CommunicationPreferences.READ_RECEIPT_ENABLED]) {
                readReceiptCount = 0
            }
            val recipient = getRecipientFor(address)
            val attachments = attachmentDatabase.getAttachment(
                cursor
            )
            val previews: List<LinkPreview?> = getLinkPreviews(cursor, attachments)
            val previewAttachments: Set<Attachment?> =
                previews.mapNotNull { it?.thumbnail }.toSet()
            val slideDeck = getSlideDeck(
                attachments
                    .filterNot { o: DatabaseAttachment? -> o in previewAttachments }
            )
            val quote = if (getQuote) getQuote(cursor) else null
            val reactions = reactionDatabase.getReactions(cursor)
            val messageContent = runCatching {
                messageContentJson?.takeIf { it.isNotBlank() }
                    ?.let { json.decodeFromString<MessageContent>(it) }
            }.onFailure {
                Log.e(TAG, "Failed to decode message content", it)
            }.getOrNull()

            val serverHash = cursor.getString(cursor.getColumnIndexOrThrow(MmsSmsColumns.SERVER_HASH))

            return MediaMmsMessageRecord(
                /* id = */ id,
                /* conversationRecipient = */ recipient,
                /* individualRecipient = */ recipient,
                /* dateSent = */ dateSent,
                /* dateReceived = */ dateReceived,
                /* deliveryReceiptCount = */ deliveryReceiptCount,
                /* threadId = */ threadId,
                /* body = */ body,
                /* slideDeck = */ slideDeck!!,
                /* mailbox = */ box,
                /* expiresIn = */ expiresIn,
                /* expireStarted = */ expireStarted,
                /* readReceiptCount = */ readReceiptCount,
                /* quote = */ quote,
                /* linkPreviews = */ previews,
                /* reactions = */ reactions,
                /* hasMention = */ hasMention,
                /* messageContent = */ messageContent,
                /* proFeatures = */ proFeatures,
                /* serverHash = */ serverHash
            )
        }

        private fun getRecipientFor(serialized: String): Recipient {
            return recipientRepository.getRecipientSync(serialized.toAddress())
        }

        private fun getSlideDeck(attachments: List<DatabaseAttachment?>): SlideDeck {
            val messageAttachments: List<Attachment?> =
                attachments.filterNot { it?.isQuote == true }
            return SlideDeck(context, messageAttachments)
        }

        private fun getQuote(cursor: Cursor): Quote? {
            val quoteId = cursor.getLong(cursor.getColumnIndexOrThrow(QUOTE_ID))
            val quoteAuthor = cursor.getString(cursor.getColumnIndexOrThrow(QUOTE_AUTHOR))
            val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(THREAD_ID))
            if (quoteId == 0L || quoteAuthor.isNullOrBlank()) return null
            val retrievedQuote = mmsSmsDatabase.get().getMessageFor(threadId, quoteId, quoteAuthor, false)
            val quoteText = retrievedQuote?.body
            val quoteMissing = retrievedQuote == null
            val quoteDeck = (
                    (retrievedQuote as? MmsMessageRecord)?.slideDeck
                        ?: attachmentDatabase.getAttachment(cursor)
                            .filter { it?.isQuote == true }
                            .let { SlideDeck(context, it) }
                    )
            return Quote(
                quoteId,
                recipientRepository.getRecipientSync(quoteAuthor.toAddress()),
                quoteText,
                quoteMissing,
                quoteDeck
            )
        }

        override fun close() {
            cursor?.close()
        }
    }

    companion object {
        private val TAG = MmsDatabase::class.java.simpleName
        const val TABLE_NAME: String = "mms"
        const val DATE_SENT: String = "date"
        const val DATE_RECEIVED: String = "date_received"
        const val MESSAGE_BOX: String = "msg_box"
        @Deprecated("No longer used.")
        const val CONTENT_LOCATION: String = "ct_l"
        const val EXPIRY: String = "exp"

        @kotlin.Deprecated(message = "No longer used.")
        const val MESSAGE_TYPE: String = "m_type"
        const val MESSAGE_SIZE: String = "m_size"
        const val STATUS: String = "st"
        @Deprecated("No longer used.")
        const val TRANSACTION_ID: String = "tr_id"
        @Deprecated("No longer used.")
        const val PART_COUNT: String = "part_count"
        @Deprecated("No longer used.")
        const val NETWORK_FAILURE: String = "network_failures"
        const val QUOTE_ID: String = "quote_id"
        const val QUOTE_AUTHOR: String = "quote_author"
        const val QUOTE_BODY: String = "quote_body"
        const val QUOTE_ATTACHMENT: String = "quote_attachment"
        const val QUOTE_MISSING: String = "quote_missing"
        @Deprecated("No longer used.")
        const val SHARED_CONTACTS: String = "shared_contacts"
        const val LINK_PREVIEWS: String = "previews"

        /**
         * The column that holds [MessageContent] in a JSON format.
         *
         * Note that this is a new column that we try to slowly migrate to, to store
         * all the message content information in a single column. Right now the [MmsSmsColumns.BODY] column
         * coexists alongside this column: if you see a [MessageContent], it takes precedence
         * over the [MmsSmsColumns.BODY]/[MESSAGE_BOX]. If it's null, then we will still use
         * the old way of describe what a message is.
         */
        const val MESSAGE_CONTENT = "message_content"


        private const val IS_DELETED_COLUMN_DEF = """
            $IS_DELETED GENERATED ALWAYS AS (
                    ($MESSAGE_BOX & ${MmsSmsColumns.Types.BASE_TYPE_MASK}) IN (${MmsSmsColumns.Types.BASE_DELETED_OUTGOING_TYPE}, ${MmsSmsColumns.Types.BASE_DELETED_INCOMING_TYPE})
                ) VIRTUAL
        """

        const val CREATE_TABLE: String =
            """CREATE TABLE $TABLE_NAME (
                $ID INTEGER PRIMARY KEY AUTOINCREMENT, 
                $THREAD_ID INTEGER, 
                $DATE_SENT INTEGER, 
                $DATE_RECEIVED INTEGER, 
                $MESSAGE_BOX INTEGER, 
                $READ INTEGER DEFAULT 0, 
                m_id TEXT, 
                sub TEXT, 
                sub_cs INTEGER, 
                $BODY TEXT, 
                $PART_COUNT INTEGER, 
                ct_t TEXT, 
                $CONTENT_LOCATION TEXT, 
                $ADDRESS TEXT, 
                $ADDRESS_DEVICE_ID INTEGER, 
                $EXPIRY INTEGER, 
                m_cls TEXT, 
                $MESSAGE_TYPE INTEGER, 
                v INTEGER, 
                $MESSAGE_SIZE INTEGER, 
                pri INTEGER, 
                rr INTEGER, 
                rpt_a INTEGER, 
                resp_st INTEGER, 
                $STATUS INTEGER, 
                $TRANSACTION_ID TEXT, 
                retr_st INTEGER, 
                retr_txt TEXT, 
                retr_txt_cs INTEGER, 
                read_status INTEGER, 
                ct_cls INTEGER, 
                resp_txt TEXT, 
                d_tm INTEGER, 
                $DELIVERY_RECEIPT_COUNT INTEGER DEFAULT 0, 
                $MISMATCHED_IDENTITIES TEXT DEFAULT NULL, 
                $NETWORK_FAILURE TEXT DEFAULT NULL,
                d_rpt INTEGER, 
                $SUBSCRIPTION_ID INTEGER DEFAULT -1, 
                $EXPIRES_IN INTEGER DEFAULT 0, 
                $EXPIRE_STARTED INTEGER DEFAULT 0, 
                $NOTIFIED INTEGER DEFAULT 0, 
                $READ_RECEIPT_COUNT INTEGER DEFAULT 0, 
                $QUOTE_ID INTEGER DEFAULT 0, 
                $QUOTE_AUTHOR TEXT, 
                $QUOTE_BODY TEXT, 
                $QUOTE_ATTACHMENT INTEGER DEFAULT -1, 
                $QUOTE_MISSING INTEGER DEFAULT 0, 
                $SHARED_CONTACTS TEXT, 
                $UNIDENTIFIED INTEGER DEFAULT 0, 
                $LINK_PREVIEWS TEXT,
                $IS_DELETED_COLUMN_DEF);"""

        @JvmField
        val CREATE_INDEXS: Array<String> = arrayOf(
            "CREATE INDEX IF NOT EXISTS mms_thread_id_index ON $TABLE_NAME ($THREAD_ID);",
            "CREATE INDEX IF NOT EXISTS mms_read_index ON $TABLE_NAME ($READ);",
            "CREATE INDEX IF NOT EXISTS mms_read_and_notified_and_thread_id_index ON $TABLE_NAME($READ,$NOTIFIED,$THREAD_ID);",
            "CREATE INDEX IF NOT EXISTS mms_message_box_index ON $TABLE_NAME ($MESSAGE_BOX);",
            "CREATE INDEX IF NOT EXISTS mms_date_sent_index ON $TABLE_NAME ($DATE_SENT);",
            "CREATE INDEX IF NOT EXISTS mms_thread_date_index ON $TABLE_NAME ($THREAD_ID, $DATE_RECEIVED);"
        )

        const val ADD_IS_DELETED_COLUMN: String = "ALTER TABLE $TABLE_NAME ADD COLUMN $IS_DELETED_COLUMN_DEF"
        const val ADD_IS_GROUP_UPDATE_COLUMN: String =
            "ALTER TABLE $TABLE_NAME ADD COLUMN $IS_GROUP_UPDATE BOOL GENERATED ALWAYS AS ($MESSAGE_BOX & ${MmsSmsColumns.Types.GROUP_UPDATE_MESSAGE_BIT} != 0) VIRTUAL"

        const val ADD_MESSAGE_CONTENT_COLUMN: String =
            "ALTER TABLE $TABLE_NAME ADD COLUMN $MESSAGE_CONTENT TEXT DEFAULT NULL"

        // This migration looks for messages with EXPIRATION_TIMER_UPDATE_BIT set,
        // then create a message content with json type = 'disappearing_message_update' and remove the bit
        const val MIGRATE_EXPIRY_CONTROL_MESSAGES = """
            UPDATE $TABLE_NAME 
            SET $MESSAGE_CONTENT = json_object(
                    '${MessageContent.DISCRIMINATOR}', '${DisappearingMessageUpdate.TYPE_NAME}', 
                    '${DisappearingMessageUpdate.KEY_EXPIRY_TIME_SECONDS}', $EXPIRES_IN / 1000, 
                    '${DisappearingMessageUpdate.KEY_EXPIRY_TYPE}', 
                        iif($EXPIRES_IN <= 0, '${DisappearingMessageUpdate.EXPIRY_MODE_NONE}',
                          iif($EXPIRE_STARTED == $DATE_SENT, ${DisappearingMessageUpdate.EXPIRY_MODE_AFTER_SENT}, ${DisappearingMessageUpdate.EXPIRY_MODE_AFTER_READ}))
                ),
                $MESSAGE_BOX = $MESSAGE_BOX & ~${MmsSmsColumns.Types.EXPIRATION_TIMER_UPDATE_BIT}
            WHERE ($MESSAGE_BOX & ${MmsSmsColumns.Types.EXPIRATION_TIMER_UPDATE_BIT}) != 0;
        """

        const val ADD_LAST_MESSAGE_INDEX: String =
            "CREATE INDEX mms_thread_id_date_sent_index ON $TABLE_NAME ($THREAD_ID, $DATE_SENT)"

        const val CREATE_MESSAGE_REQUEST_RESPONSE_COMMAND = "ALTER TABLE $TABLE_NAME ADD COLUMN $MESSAGE_REQUEST_RESPONSE INTEGER DEFAULT 0;"
        const val CREATE_REACTIONS_UNREAD_COMMAND = "ALTER TABLE $TABLE_NAME ADD COLUMN $REACTIONS_UNREAD INTEGER DEFAULT 0;"
        const val CREATE_REACTIONS_LAST_SEEN_COMMAND = "ALTER TABLE $TABLE_NAME ADD COLUMN $REACTIONS_LAST_SEEN INTEGER DEFAULT 0;"
        const val CREATE_HAS_MENTION_COMMAND = "ALTER TABLE $TABLE_NAME ADD COLUMN $HAS_MENTION INTEGER DEFAULT 0;"

        private const val TEMP_TABLE_NAME = "TEMP_TABLE_NAME"

        const val COMMA_SEPARATED_COLUMNS = "$ID, $THREAD_ID, $DATE_SENT, $DATE_RECEIVED, $MESSAGE_BOX, $READ, m_id, sub, sub_cs, $BODY, $PART_COUNT, ct_t, $CONTENT_LOCATION, $ADDRESS, $ADDRESS_DEVICE_ID, $EXPIRY, m_cls, $MESSAGE_TYPE, v, $MESSAGE_SIZE, pri, rr,rpt_a, resp_st, $STATUS, $TRANSACTION_ID, retr_st, retr_txt, retr_txt_cs, read_status, ct_cls, resp_txt, d_tm, $DELIVERY_RECEIPT_COUNT, $MISMATCHED_IDENTITIES, $NETWORK_FAILURE, d_rpt, $SUBSCRIPTION_ID, $EXPIRES_IN, $EXPIRE_STARTED, $NOTIFIED, $READ_RECEIPT_COUNT, $QUOTE_ID, $QUOTE_AUTHOR, $QUOTE_BODY, $QUOTE_ATTACHMENT, $QUOTE_MISSING, $SHARED_CONTACTS, $UNIDENTIFIED, $LINK_PREVIEWS, $MESSAGE_REQUEST_RESPONSE, $REACTIONS_UNREAD, $REACTIONS_LAST_SEEN, $HAS_MENTION"

        @JvmField
        val ADD_AUTOINCREMENT = arrayOf(
            "ALTER TABLE $TABLE_NAME RENAME TO $TEMP_TABLE_NAME",
            CREATE_TABLE,
            CREATE_MESSAGE_REQUEST_RESPONSE_COMMAND,
            CREATE_REACTIONS_UNREAD_COMMAND,
            CREATE_REACTIONS_LAST_SEEN_COMMAND,
            CREATE_HAS_MENTION_COMMAND,
            "INSERT INTO $TABLE_NAME ($COMMA_SEPARATED_COLUMNS) SELECT $COMMA_SEPARATED_COLUMNS FROM $TEMP_TABLE_NAME",
            "DROP TABLE $TEMP_TABLE_NAME"
        )

        fun addProFeatureColumns(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $PRO_PROFILE_FEATURES INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $PRO_MESSAGE_FEATURES INTEGER NOT NULL DEFAULT 0")
        }

        fun addOutgoingColumn(db: SupportSQLiteDatabase) {
            val outgoingTypeSet = MmsSmsColumns.Types.OUTGOING_MESSAGE_TYPES.joinToString(separator = ",", prefix = "(", postfix = ")")
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $IS_OUTGOING BOOLEAN GENERATED ALWAYS AS (($MESSAGE_BOX & ${MmsSmsColumns.Types.BASE_TYPE_MASK}) IN ${outgoingTypeSet}) VIRTUAL")
        }
    }
}
