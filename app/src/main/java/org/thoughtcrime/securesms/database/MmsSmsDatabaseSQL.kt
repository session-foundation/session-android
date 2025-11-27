package org.thoughtcrime.securesms.database

import org.thoughtcrime.securesms.database.model.MessageId

/**
 * Build a combined query to fetch both MMS and SMS messages in one go, the high level idea is to
 * use a UNION between two SELECT statements, one for MMS and one for SMS. And they will need
 * to have the same projection so we'll also do some aliasing on them. This can be illustrated as:
 *
 * For each message, we will perform sub-query to reaction/attachment database to query the relevant
 * data. We try not to use JOIN as they screw up performance by impacting the index selection.
 *
 * ```sqlite
 * SELECT sms_fields,
 *  (query reaction table) AS reactions,
 *  NULL AS attachments,
 *  (query hash table) AS server_hash
 * FROM sms
 *
 * UNION ALL
 *
 * SELECT
 *  mms_fields,
 *  (query reaction table) AS reactions,
 *  (query attachment table) AS attachments,
 *  (query hash table) AS server_hash
 * FROM mms
 * ```
 */
fun buildMmsSmsCombinedQuery(
    projection: String,
    selection: String?,
    includeReactions: Boolean,
    reactionSelection: String?,
    order: String?,
    limit: String?
): String {
    // The query parts that fetch all reactions for a given message, and group them into a JSON array
    val reactionsQueryParts = """
        SELECT json_group_array(
            json_object(
                '${ReactionDatabase.ROW_ID}', ${ReactionDatabase.TABLE_NAME}.${ReactionDatabase.ROW_ID},
                '${ReactionDatabase.MESSAGE_ID}', ${ReactionDatabase.TABLE_NAME}.${ReactionDatabase.MESSAGE_ID},
                '${ReactionDatabase.IS_MMS}', ${ReactionDatabase.TABLE_NAME}.${ReactionDatabase.IS_MMS}, 
                '${ReactionDatabase.AUTHOR_ID}', ${ReactionDatabase.TABLE_NAME}.${ReactionDatabase.AUTHOR_ID}, 
                '${ReactionDatabase.EMOJI}', ${ReactionDatabase.TABLE_NAME}.${ReactionDatabase.EMOJI}, 
                '${ReactionDatabase.SERVER_ID}', ${ReactionDatabase.TABLE_NAME}.${ReactionDatabase.SERVER_ID}, 
                '${ReactionDatabase.COUNT}', ${ReactionDatabase.TABLE_NAME}.${ReactionDatabase.COUNT}, 
                '${ReactionDatabase.SORT_ID}', ${ReactionDatabase.TABLE_NAME}.${ReactionDatabase.SORT_ID}, 
                '${ReactionDatabase.DATE_SENT}', ${ReactionDatabase.TABLE_NAME}.${ReactionDatabase.DATE_SENT}, 
                '${ReactionDatabase.DATE_RECEIVED}', ${ReactionDatabase.TABLE_NAME}.${ReactionDatabase.DATE_RECEIVED}
            )
        )
        FROM ${ReactionDatabase.TABLE_NAME}
        """

    // Subquery to grab sms' server hash
    val smsHashQuery = """
        SELECT server_hash
        FROM ${LokiMessageDatabase.smsHashTable} sms_hash
        WHERE sms_hash.message_id = ${SmsDatabase.TABLE_NAME}.${MmsSmsColumns.ID}
    """

    // Custom where statement for reactions if provided
    val additionalReactionSelection = reactionSelection?.let { " AND ($it)" }.orEmpty()


    // If reactions are not requested, we just return an empty JSON array
    val smsReactionQuery = if (includeReactions) {
        """($reactionsQueryParts 
            WHERE 
                ${ReactionDatabase.TABLE_NAME}.${ReactionDatabase.MESSAGE_ID} = ${SmsDatabase.TABLE_NAME}.${MmsSmsColumns.ID} 
                AND NOT ${ReactionDatabase.TABLE_NAME}.${ReactionDatabase.IS_MMS}
                $additionalReactionSelection)"""
    } else {
        "'[]'"
    }

    val whereStatement = selection?.let { "WHERE $it" }.orEmpty()

    // The main query for SMS messages
    val smsQuery = """
        SELECT
            ${SmsDatabase.DATE_SENT} AS ${MmsSmsColumns.NORMALIZED_DATE_SENT},
            ${SmsDatabase.DATE_RECEIVED} AS ${MmsSmsColumns.NORMALIZED_DATE_RECEIVED},
            ${MmsSmsColumns.ID},
            'SMS::' || ${MmsSmsColumns.ID} || '::' || ${SmsDatabase.DATE_SENT} AS ${MmsSmsColumns.UNIQUE_ROW_ID},
            NULL AS ${AttachmentDatabase.ATTACHMENT_JSON_ALIAS},
            $smsReactionQuery AS ${ReactionDatabase.REACTION_JSON_ALIAS},
            ${SmsDatabase.BODY},
            NULL AS ${MmsSmsColumns.MESSAGE_CONTENT},
            ${MmsSmsColumns.READ},
            ${MmsSmsColumns.THREAD_ID},
            ${SmsDatabase.TYPE},
            ${SmsDatabase.ADDRESS},
            NULL AS ${MmsDatabase.MESSAGE_TYPE},
            NULL AS ${MmsDatabase.MESSAGE_BOX},
            ${SmsDatabase.STATUS},
            NULL AS ${MmsDatabase.MESSAGE_SIZE},
            NULL AS ${MmsDatabase.EXPIRY},
            NULL AS ${MmsDatabase.STATUS},
            ${MmsSmsColumns.DELIVERY_RECEIPT_COUNT},
            ${MmsSmsColumns.READ_RECEIPT_COUNT},
            ${MmsSmsColumns.EXPIRES_IN},
            ${MmsSmsColumns.EXPIRE_STARTED},
            ${MmsSmsColumns.NOTIFIED},
            '${MmsSmsDatabase.SMS_TRANSPORT}' AS ${MmsSmsDatabase.TRANSPORT},
            NULL AS ${MmsDatabase.QUOTE_ID},
            NULL AS ${MmsDatabase.QUOTE_AUTHOR},
            NULL AS ${MmsDatabase.QUOTE_BODY},
            NULL AS ${MmsDatabase.QUOTE_MISSING},
            NULL AS ${MmsDatabase.QUOTE_ATTACHMENT},
            NULL AS ${MmsDatabase.LINK_PREVIEWS},
            ${MmsSmsColumns.HAS_MENTION},
            ($smsHashQuery) AS ${MmsSmsColumns.SERVER_HASH},
            ${MmsSmsColumns.PRO_FEATURES}
        FROM ${SmsDatabase.TABLE_NAME}
        $whereStatement
    """

    // Subquery to grab mms' server hash
    val mmsHashQuery = """
        SELECT server_hash
        FROM ${LokiMessageDatabase.mmsHashTable} mms_hash
        WHERE mms_hash.message_id = ${MmsDatabase.TABLE_NAME}.${MmsSmsColumns.ID}
    """

    // The subquery that fetches all attachments for a given MMS message, and group them into a JSON array
    val attachmentQuery = """
        SELECT json_group_array(
            json_object(
                '${AttachmentDatabase.ROW_ID}', a.${AttachmentDatabase.ROW_ID}, 
                '${AttachmentDatabase.UNIQUE_ID}', a.${AttachmentDatabase.UNIQUE_ID}, 
                '${AttachmentDatabase.MMS_ID}', a.${AttachmentDatabase.MMS_ID},
                '${AttachmentDatabase.SIZE}', a.${AttachmentDatabase.SIZE}, 
                '${AttachmentDatabase.FILE_NAME}', a.${AttachmentDatabase.FILE_NAME}, 
                '${AttachmentDatabase.DATA}', a.${AttachmentDatabase.DATA}, 
                '${AttachmentDatabase.THUMBNAIL}', a.${AttachmentDatabase.THUMBNAIL}, 
                '${AttachmentDatabase.CONTENT_TYPE}', a.${AttachmentDatabase.CONTENT_TYPE}, 
                '${AttachmentDatabase.CONTENT_LOCATION}', a.${AttachmentDatabase.CONTENT_LOCATION}, 
                '${AttachmentDatabase.FAST_PREFLIGHT_ID}', a.${AttachmentDatabase.FAST_PREFLIGHT_ID}, 
                '${AttachmentDatabase.VOICE_NOTE}', a.${AttachmentDatabase.VOICE_NOTE}, 
                '${AttachmentDatabase.WIDTH}', a.${AttachmentDatabase.WIDTH}, 
                '${AttachmentDatabase.HEIGHT}', a.${AttachmentDatabase.HEIGHT}, 
                '${AttachmentDatabase.QUOTE}', a.${AttachmentDatabase.QUOTE}, 
                '${AttachmentDatabase.CONTENT_DISPOSITION}', a.${AttachmentDatabase.CONTENT_DISPOSITION}, 
                '${AttachmentDatabase.NAME}', a.${AttachmentDatabase.NAME}, 
                '${AttachmentDatabase.TRANSFER_STATE}', a.${AttachmentDatabase.TRANSFER_STATE}, 
                '${AttachmentDatabase.CAPTION}', a.${AttachmentDatabase.CAPTION}, 
                '${AttachmentDatabase.STICKER_PACK_ID}', a.${AttachmentDatabase.STICKER_PACK_ID}, 
                '${AttachmentDatabase.STICKER_PACK_KEY}', a.${AttachmentDatabase.STICKER_PACK_KEY}, 
                '${AttachmentDatabase.AUDIO_DURATION}', ifnull(a.${AttachmentDatabase.AUDIO_DURATION}, -1), 
                '${AttachmentDatabase.STICKER_ID}', a.${AttachmentDatabase.STICKER_ID}
            )
        )
        FROM ${AttachmentDatabase.TABLE_NAME} AS a
        WHERE a.${AttachmentDatabase.MMS_ID} = ${MmsDatabase.TABLE_NAME}.${MmsSmsColumns.ID}
    """

    // Custom where statement for reactions if provided
    val mmsReactionQuery = if (includeReactions) {
        """($reactionsQueryParts 
            WHERE 
                ${ReactionDatabase.TABLE_NAME}.${ReactionDatabase.MESSAGE_ID} = ${MmsDatabase.TABLE_NAME}.${MmsSmsColumns.ID} 
                AND ${ReactionDatabase.TABLE_NAME}.${ReactionDatabase.IS_MMS}
                $additionalReactionSelection)"""
    } else {
        "'[]'"
    }

    // The main query for MMS messages
    val mmsQuery = """
        SELECT
            ${MmsDatabase.DATE_SENT} AS ${MmsSmsColumns.NORMALIZED_DATE_SENT},
            ${MmsDatabase.DATE_RECEIVED} AS ${MmsSmsColumns.NORMALIZED_DATE_RECEIVED},
            ${MmsDatabase.TABLE_NAME}.${MmsSmsColumns.ID} AS ${MmsSmsColumns.ID},
            'MMS::' || ${MmsDatabase.TABLE_NAME}.${MmsSmsColumns.ID} || '::' || ${MmsDatabase.DATE_SENT} AS ${MmsSmsColumns.UNIQUE_ROW_ID},
            ($attachmentQuery) AS ${AttachmentDatabase.ATTACHMENT_JSON_ALIAS},
            $mmsReactionQuery AS ${ReactionDatabase.REACTION_JSON_ALIAS},
            ${MmsSmsColumns.BODY},
            ${MmsSmsColumns.MESSAGE_CONTENT},
            ${MmsSmsColumns.READ},
            ${MmsSmsColumns.THREAD_ID},
            NULL AS ${SmsDatabase.TYPE},
            ${MmsSmsColumns.ADDRESS},
            ${MmsDatabase.MESSAGE_TYPE},
            ${MmsDatabase.MESSAGE_BOX},
            NULL AS ${SmsDatabase.STATUS},
            ${MmsDatabase.MESSAGE_SIZE},
            ${MmsDatabase.EXPIRY},
            ${MmsDatabase.STATUS},
            ${MmsSmsColumns.DELIVERY_RECEIPT_COUNT},
            ${MmsSmsColumns.READ_RECEIPT_COUNT},
            ${MmsSmsColumns.EXPIRES_IN},
            ${MmsSmsColumns.EXPIRE_STARTED},
            ${MmsSmsColumns.NOTIFIED},
            '${MmsSmsDatabase.MMS_TRANSPORT}' AS ${MmsSmsDatabase.TRANSPORT},
            ${MmsDatabase.QUOTE_ID},
            ${MmsDatabase.QUOTE_AUTHOR},
            ${MmsDatabase.QUOTE_BODY},
            ${MmsDatabase.QUOTE_MISSING},
            ${MmsDatabase.QUOTE_ATTACHMENT},
            ${MmsDatabase.LINK_PREVIEWS},
            ${MmsSmsColumns.HAS_MENTION},
            ($mmsHashQuery) AS ${MmsSmsColumns.SERVER_HASH},
            ${MmsSmsColumns.PRO_FEATURES}
        FROM ${MmsDatabase.TABLE_NAME}
        $whereStatement
    """

    val orderStatement = order?.let { "ORDER BY $it" }.orEmpty()
    val limitStatement = limit?.let { "LIMIT $it" }.orEmpty()

    return """
        WITH combined AS (
            $smsQuery
            UNION ALL
            $mmsQuery
        )
        
        SELECT $projection
        FROM combined
        $orderStatement
        $limitStatement
    """
}

/**
 * Build a query to get the maximum timestamp (date sent) in a thread up to and including
 * the timestamp of the given message ID.
 *
 * This query will also look at reactions associated with messages in the thread
 * to ensure that if there are reactions with later timestamps, they are considered
 * as well.
 *
 * @return A pair containing the SQL query string and an array of parameters to bind.
 *         The query will return at most one row of "maxTimestamp", "threadId".
 */
fun buildMaxTimestampInThreadUpToQuery(id: MessageId): Pair<String, Array<Any>> {
    val msgTable = if (id.mms) MmsDatabase.TABLE_NAME else SmsDatabase.TABLE_NAME
    val dateSentColumn = if (id.mms) MmsDatabase.DATE_SENT else SmsDatabase.DATE_SENT
    val threadIdColumn = if (id.mms) MmsSmsColumns.THREAD_ID else SmsDatabase.THREAD_ID

    // The query below does this:
    // 1. Query the given message, find out its thread id and its date sent
    // 2. Find all the messages in this thread before this messages (using result from step 1)
    // 3. With this message + earlier messages, grab all the reactions associated with them
    // 4. Look at the max date among the reactions returned from step 3
    // 5. Return the max between this message's date and the max reaction date
    return """
        SELECT 
            MAX(
                mainMessage.$dateSentColumn, 
                IFNULL(
                    (
                        SELECT MAX(r.${ReactionDatabase.DATE_SENT})
                        FROM ${ReactionDatabase.TABLE_NAME} r
                        INDEXED BY reaction_message_id_is_mms_index
                        WHERE (r.${ReactionDatabase.MESSAGE_ID}, r.${ReactionDatabase.IS_MMS}) IN (
                            SELECT s.${MmsSmsColumns.ID}, FALSE
                            FROM ${SmsDatabase.TABLE_NAME} s
                            WHERE s.${SmsDatabase.THREAD_ID} = mainMessage.${threadIdColumn} AND
                                s.${SmsDatabase.DATE_SENT} <= mainMessage.$dateSentColumn
                                
                            UNION ALL
                            
                            SELECT m.${MmsSmsColumns.ID}, TRUE
                            FROM ${MmsDatabase.TABLE_NAME} m
                            WHERE m.${MmsSmsColumns.THREAD_ID} = mainMessage.${threadIdColumn} AND
                                m.${MmsDatabase.DATE_SENT} <= mainMessage.$dateSentColumn
                        )
                    ),
                    0
                )
            ) AS maxTimestamp,
            mainMessage.$threadIdColumn AS threadId
        FROM $msgTable mainMessage
        WHERE mainMessage.${MmsSmsColumns.ID} = ?
    """ to arrayOf(id.id)
}