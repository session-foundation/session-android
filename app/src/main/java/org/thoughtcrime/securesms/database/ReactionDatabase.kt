package org.thoughtcrime.securesms.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONArray
import org.json.JSONException
import org.session.libsignal.utilities.JsonUtil.SaneJSONObject
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.util.CursorUtil
import org.thoughtcrime.securesms.util.asSequence
import javax.inject.Provider

/**
 * Store reactions on messages.
 */
class ReactionDatabase(context: Context, helper: Provider<SQLCipherOpenHelper>) : Database(context, helper) {

  private val mutableChangeNotification = MutableSharedFlow<MessageId>(
    extraBufferCapacity = 100,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )

  val changeNotification: Flow<MessageId> get() = mutableChangeNotification

  companion object {
    const val TABLE_NAME = "reaction"
    const val REACTION_JSON_ALIAS = "reaction_json"
    const val ROW_ID = "reaction_id"
    const val MESSAGE_ID = "message_id"
    const val IS_MMS = "is_mms"
    const val AUTHOR_ID = "author_id"
    const val SERVER_ID = "server_id"
    const val COUNT = "count"
    const val SORT_ID = "sort_id"
    const val EMOJI = "emoji"
    const val DATE_SENT = "reaction_date_sent"
    const val DATE_RECEIVED = "reaction_date_received"

    @JvmField
    val CREATE_REACTION_TABLE_COMMAND = """
      CREATE TABLE $TABLE_NAME (
        $ROW_ID INTEGER PRIMARY KEY,
        $MESSAGE_ID INTEGER NOT NULL,
        $IS_MMS INTEGER NOT NULL,
        $AUTHOR_ID INTEGER NOT NULL REFERENCES ${RecipientDatabase.TABLE_NAME} (${RecipientDatabase.ID}) ON DELETE CASCADE,
        $EMOJI TEXT NOT NULL,
        $SERVER_ID TEXT NOT NULL,
        $COUNT INTEGER NOT NULL,
        $SORT_ID INTEGER NOT NULL,
        $DATE_SENT INTEGER NOT NULL,
        $DATE_RECEIVED INTEGER NOT NULL,
        UNIQUE($MESSAGE_ID, $IS_MMS, $EMOJI, $AUTHOR_ID) ON CONFLICT REPLACE
      )
    """.trimIndent()

    @JvmField
    val CREATE_INDEXS = arrayOf(
      "CREATE INDEX IF NOT EXISTS reaction_message_id_index ON " + ReactionDatabase.TABLE_NAME + " (" + ReactionDatabase.MESSAGE_ID + ");",
      "CREATE INDEX IF NOT EXISTS reaction_is_mms_index ON " + ReactionDatabase.TABLE_NAME + " (" + ReactionDatabase.IS_MMS + ");",
      "CREATE INDEX IF NOT EXISTS reaction_message_id_is_mms_index ON " + ReactionDatabase.TABLE_NAME + " (" + ReactionDatabase.MESSAGE_ID + ", " + ReactionDatabase.IS_MMS + ");",
      "CREATE INDEX IF NOT EXISTS reaction_sort_id_index ON " + ReactionDatabase.TABLE_NAME + " (" + ReactionDatabase.SORT_ID + ");",
    )

    @JvmField
    val CREATE_REACTION_TRIGGERS = arrayOf(
      """
        CREATE TRIGGER reactions_sms_delete AFTER DELETE ON ${SmsDatabase.TABLE_NAME} 
        BEGIN 
        	DELETE FROM $TABLE_NAME WHERE $MESSAGE_ID = old.${MmsSmsColumns.ID} AND $IS_MMS = 0;
        END
      """,
      """
        CREATE TRIGGER reactions_mms_delete AFTER DELETE ON ${MmsDatabase.TABLE_NAME} 
        BEGIN 
        	DELETE FROM $TABLE_NAME WHERE $MESSAGE_ID = old.${MmsSmsColumns.ID} AND $IS_MMS = 1;
        END
      """
    )

    @JvmField
    val CREATE_MESSAGE_ID_MMS_INDEX = arrayOf("CREATE INDEX IF NOT EXISTS reaction_message_id_mms_idx ON $TABLE_NAME ($MESSAGE_ID, $IS_MMS)")

    @JvmField
    val MIGRATE_REACTION_TABLE_TO_USE_RECIPIENT_SETTINGS = arrayOf(
      // Create the new table with updated schema
      """
        CREATE TABLE ${TABLE_NAME}_new (
          $ROW_ID INTEGER PRIMARY KEY,
          $MESSAGE_ID INTEGER NOT NULL,
          $IS_MMS INTEGER NOT NULL,
          $AUTHOR_ID INTEGER NOT NULL REFERENCES ${RecipientSettingsDatabase.TABLE_NAME} (${RecipientSettingsDatabase.COL_ADDRESS}) ON DELETE CASCADE,
          $EMOJI TEXT NOT NULL,
          $SERVER_ID TEXT NOT NULL,
          $COUNT INTEGER NOT NULL,
          $SORT_ID INTEGER NOT NULL,
          $DATE_SENT INTEGER NOT NULL,
          $DATE_RECEIVED INTEGER NOT NULL,
          UNIQUE($MESSAGE_ID, $IS_MMS, $EMOJI, $AUTHOR_ID) ON CONFLICT REPLACE
        )
      """,

      // Copy data from the old table to the new table
      """
        INSERT INTO ${TABLE_NAME}_new ($ROW_ID, $MESSAGE_ID, $IS_MMS, $AUTHOR_ID, $EMOJI, $SERVER_ID, $COUNT, $SORT_ID, $DATE_SENT, $DATE_RECEIVED)
        SELECT $ROW_ID, $MESSAGE_ID, $IS_MMS, ${AUTHOR_ID}, $EMOJI, $SERVER_ID, $COUNT, $SORT_ID, $DATE_SENT, $DATE_RECEIVED
        FROM $TABLE_NAME
      """,

      // Drop the old table and their triggers
      "DROP TABLE $TABLE_NAME",
      "DROP TRIGGER reactions_sms_delete",
      "DROP TRIGGER reactions_mms_delete",

      // Rename the new table to the original table name
      "ALTER TABLE ${TABLE_NAME}_new RENAME TO $TABLE_NAME",

      // Add the necessary indexes and triggers to the new table
      *CREATE_INDEXS,
      *CREATE_REACTION_TRIGGERS,
    )

    private fun readReaction(cursor: Cursor): ReactionRecord {
      return ReactionRecord(
        messageId = MessageId(CursorUtil.requireLong(cursor, MESSAGE_ID), CursorUtil.requireInt(cursor, IS_MMS) == 1),
        emoji = CursorUtil.requireString(cursor, EMOJI),
        author = CursorUtil.requireString(cursor, AUTHOR_ID),
        serverId = CursorUtil.requireString(cursor, SERVER_ID),
        count = CursorUtil.requireLong(cursor, COUNT),
        sortId = CursorUtil.requireLong(cursor, SORT_ID),
        dateSent = CursorUtil.requireLong(cursor, DATE_SENT),
        dateReceived = CursorUtil.requireLong(cursor, DATE_RECEIVED)
      )
    }
  }

  fun getReactions(messageId: MessageId): List<ReactionRecord> {
    val query = "$MESSAGE_ID = ? AND $IS_MMS = ? ORDER BY $SORT_ID"
    val args = arrayOf("${messageId.id}", "${if (messageId.mms) 1 else 0}")

    val reactions: MutableList<ReactionRecord> = mutableListOf()

    readableDatabase.query(TABLE_NAME, null, query, args, null, null, null).use { cursor ->
      while (cursor.moveToNext()) {
        reactions += readReaction(cursor)
      }
    }

    return reactions
  }

  fun addReaction(reaction: ReactionRecord) {
    addReactions(mapOf(reaction.messageId to listOf(reaction)), replaceAll = false)
  }

  fun addReactions(reactionsByMessageId: Map<MessageId, List<ReactionRecord>>, replaceAll: Boolean) {
    if (reactionsByMessageId.isEmpty()) return

    val values = ContentValues()

    writableDatabase.beginTransaction()
    try {
      // Delete existing reactions for the same message IDs if replaceAll is true
      if (replaceAll && reactionsByMessageId.isNotEmpty()) {
        // We don't need to do parameteralized queries here as messageId and isMms are always
        // integers/boolean, and hence no risk of SQL injection.
        val whereClause = StringBuilder("($MESSAGE_ID, $IS_MMS) IN (")
        for ((i, id) in reactionsByMessageId.keys.withIndex()) {
          if (i > 0) {
            whereClause.append(", ")
          }

          whereClause
            .append('(')
            .append(id.id).append(',').append(id.mms)
            .append(')')
        }
        whereClause.append(')')

        writableDatabase.delete(TABLE_NAME, whereClause.toString(), null)
      }

      reactionsByMessageId
        .asSequence()
        .flatMap { it.value.asSequence() }
        .forEach { reaction ->
            values.apply {
              put(MESSAGE_ID, reaction.messageId.id)
              put(IS_MMS, reaction.messageId.mms)
              put(EMOJI, reaction.emoji)
              put(AUTHOR_ID, reaction.author)
              put(SERVER_ID, reaction.serverId)
              put(COUNT, reaction.count)
              put(SORT_ID, reaction.sortId)
              put(DATE_SENT, reaction.dateSent)
              put(DATE_RECEIVED, reaction.dateReceived)
            }

            writableDatabase.insert(TABLE_NAME, null, values)
        }

      writableDatabase.setTransactionSuccessful()

      // Notify listeners about the change
      for (messageId in reactionsByMessageId.keys) {
        mutableChangeNotification.tryEmit(messageId)
      }
    } finally {
      writableDatabase.endTransaction()
    }
  }

  fun deleteReaction(emoji: String, messageId: MessageId, author: String) {
    deleteReactions(
      query = "$MESSAGE_ID = ? AND $IS_MMS = ? AND $EMOJI = ? AND $AUTHOR_ID = ?",
      args = arrayOf("${messageId.id}", "${if (messageId.mms)  1 else 0}", emoji, author),
    )
  }

  fun deleteEmojiReactions(emoji: String, messageId: MessageId) {
    deleteReactions(
      query = "$MESSAGE_ID = ? AND $IS_MMS = ? AND $EMOJI = ?",
      args = arrayOf("${messageId.id}", "${if (messageId.mms)  1 else 0}", emoji),
    )
  }

  fun deleteMessageReactions(messageId: MessageId) {
    deleteReactions(
      query = "$MESSAGE_ID = ? AND $IS_MMS = ?",
      args = arrayOf("${messageId.id}", "${if (messageId.mms)  1 else 0}"),
    )
  }

  fun deleteMessageReactions(messageIds: List<MessageId>) {
      if (messageIds.isEmpty()) return  // Early exit if the list is empty

      val conditions = mutableListOf<String>()
      val args = mutableListOf<String>()

      for (messageId in messageIds) {
          conditions.add("($MESSAGE_ID = ? AND $IS_MMS = ?)")
          args.add(messageId.id.toString())
          args.add(if (messageId.mms) "1" else "0")
      }

      val query = conditions.joinToString(" OR ")

      deleteReactions(
        query = query,
        args = args.toTypedArray()
      )
  }

  private fun deleteReactions(query: String, args: Array<String>) {
    val updatedMessageIDs = writableDatabase.rawQuery("DELETE FROM $TABLE_NAME WHERE $query RETURNING $MESSAGE_ID, $IS_MMS", *args).use { cursor ->
      cursor.asSequence()
        .mapTo(hashSetOf()) { MessageId(id = it.getLong(0), mms = it.getInt(1) != 0) }
    }

    for (messageId in updatedMessageIDs) {
      mutableChangeNotification.tryEmit(messageId)
    }
  }

  fun getReactions(cursor: Cursor): List<ReactionRecord> {
    return try {
      if (cursor.getColumnIndex(REACTION_JSON_ALIAS) != -1) {
        if (cursor.isNull(cursor.getColumnIndexOrThrow(REACTION_JSON_ALIAS))) {
          return listOf()
        }
        val result = mutableSetOf<ReactionRecord>()
        val array = JSONArray(cursor.getString(cursor.getColumnIndexOrThrow(REACTION_JSON_ALIAS)))
        for (i in 0 until array.length()) {
          val obj = SaneJSONObject(array.getJSONObject(i))
          if (!obj.isNull(ROW_ID)) {
            result.add(
              ReactionRecord(
                id = obj.getLong(ROW_ID),
                messageId = MessageId(obj.getLong(MESSAGE_ID), obj.getInt(IS_MMS) == 1),
                author = obj.getString(AUTHOR_ID),
                emoji = obj.getString(EMOJI),
                serverId = obj.getString(SERVER_ID),
                count = obj.getLong(COUNT),
                sortId = obj.getLong(SORT_ID),
                dateSent = obj.getLong(DATE_SENT),
                dateReceived = obj.getLong(DATE_RECEIVED)
              )
            )
          }
        }
        result.sortedBy { it.dateSent }
      } else {
        listOf(
          ReactionRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(ROW_ID)),
            messageId = MessageId(cursor.getLong(MESSAGE_ID), cursor.getInt(IS_MMS) == 1),
            author = cursor.getString(cursor.getColumnIndexOrThrow(AUTHOR_ID)),
            emoji = cursor.getString(cursor.getColumnIndexOrThrow(EMOJI)),
            serverId = cursor.getString(cursor.getColumnIndexOrThrow(SERVER_ID)),
            count = cursor.getLong(cursor.getColumnIndexOrThrow(COUNT)),
            sortId = cursor.getLong(cursor.getColumnIndexOrThrow(SORT_ID)),
            dateSent = cursor.getLong(cursor.getColumnIndexOrThrow(DATE_SENT)),
            dateReceived = cursor.getLong(cursor.getColumnIndexOrThrow(DATE_RECEIVED))
          )
        )
      }
    } catch (e: JSONException) {
      throw AssertionError(e)
    }
  }

  fun getReactionFor(timestamp: Long, sender: String): ReactionRecord? {
    val query = "$DATE_SENT = ? AND $AUTHOR_ID = ?"
    val args = arrayOf("$timestamp", sender)

    readableDatabase.query(TABLE_NAME, null, query, args, null, null, null).use { cursor ->
      return if (cursor.moveToFirst()) readReaction(cursor) else null
    }
  }

  fun updateReaction(reaction: ReactionRecord) {
    writableDatabase.beginTransaction()
    try {
      val values = ContentValues().apply {
        put(EMOJI, reaction.emoji)
        put(AUTHOR_ID, reaction.author)
        put(SERVER_ID, reaction.serverId)
        put(COUNT, reaction.count)
        put(SORT_ID, reaction.sortId)
        put(DATE_SENT, reaction.dateSent)
        put(DATE_RECEIVED, reaction.dateReceived)
      }

      val query = "$ROW_ID = ?"
      val args = arrayOf("${reaction.id}")
      writableDatabase.update(TABLE_NAME, values, query, args)

      writableDatabase.setTransactionSuccessful()

      // Notify listeners about the change
      mutableChangeNotification.tryEmit(reaction.messageId)
    } finally {
      writableDatabase.endTransaction()
    }
  }

}
