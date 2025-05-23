package org.thoughtcrime.securesms.database;

import static org.thoughtcrime.securesms.database.UtilKt.generatePlaceholders;

import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;

import com.annimon.stream.Stream;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.session.libsession.utilities.Util;

import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.inject.Provider;

/**
 * Contains all databases necessary for full-text search (FTS).
 */
public class SearchDatabase extends Database {

  public static final String SMS_FTS_TABLE_NAME = "sms_fts";
  public static final String MMS_FTS_TABLE_NAME = "mms_fts";

  public static final String ID                   = "rowid";
  public static final String BODY                 = MmsSmsColumns.BODY;
  public static final String THREAD_ID            = MmsSmsColumns.THREAD_ID;
  public static final String SNIPPET              = "snippet";
  public static final String CONVERSATION_ADDRESS = "conversation_address";
  public static final String MESSAGE_ADDRESS      = "message_address";

  public static final String[] CREATE_TABLE = {
          "CREATE VIRTUAL TABLE " + SMS_FTS_TABLE_NAME + " USING fts5(" + BODY + ", " + THREAD_ID + " UNINDEXED, content=" + SmsDatabase.TABLE_NAME + ", content_rowid=" + SmsDatabase.ID + ");",

          "CREATE TRIGGER sms_ai AFTER INSERT ON " + SmsDatabase.TABLE_NAME + " BEGIN\n" +
                  "  INSERT INTO " + SMS_FTS_TABLE_NAME + "(" + ID + ", " + BODY + ", " + THREAD_ID + ") VALUES (new." + SmsDatabase.ID + ", new." + SmsDatabase.BODY + ", new." + SmsDatabase.THREAD_ID + ");\n" +
                  "END;\n",
          "CREATE TRIGGER sms_ad AFTER DELETE ON " + SmsDatabase.TABLE_NAME + " BEGIN\n" +
                  "  INSERT INTO " + SMS_FTS_TABLE_NAME + "(" + SMS_FTS_TABLE_NAME + ", " + ID + ", " + BODY + ", " + THREAD_ID + ") VALUES('delete', old." + SmsDatabase.ID + ", old." + SmsDatabase.BODY + ", old." + SmsDatabase.THREAD_ID + ");\n" +
                  "END;\n",
          "CREATE TRIGGER sms_au AFTER UPDATE ON " + SmsDatabase.TABLE_NAME + " BEGIN\n" +
                  "  INSERT INTO " + SMS_FTS_TABLE_NAME + "(" + SMS_FTS_TABLE_NAME + ", " + ID + ", " + BODY + ", " + THREAD_ID + ") VALUES('delete', old." + SmsDatabase.ID + ", old." + SmsDatabase.BODY + ", old." + SmsDatabase.THREAD_ID + ");\n" +
                  "  INSERT INTO " + SMS_FTS_TABLE_NAME + "(" + ID + ", " + BODY + ", " + THREAD_ID + ") VALUES(new." + SmsDatabase.ID + ", new." + SmsDatabase.BODY + ", new." + SmsDatabase.THREAD_ID + ");\n" +
                  "END;",


          "CREATE VIRTUAL TABLE " + MMS_FTS_TABLE_NAME + " USING fts5(" + BODY + ", " + THREAD_ID + " UNINDEXED, content=" + MmsDatabase.TABLE_NAME + ", content_rowid=" + MmsDatabase.ID + ");",

          "CREATE TRIGGER mms_ai AFTER INSERT ON " + MmsDatabase.TABLE_NAME + " BEGIN\n" +
                  "  INSERT INTO " + MMS_FTS_TABLE_NAME + "(" + ID + ", " + BODY + ", " + THREAD_ID + ") VALUES (new." + MmsDatabase.ID + ", new." + MmsDatabase.BODY + ", new." + MmsDatabase.THREAD_ID + ");\n" +
                  "END;\n",
          "CREATE TRIGGER mms_ad AFTER DELETE ON " + MmsDatabase.TABLE_NAME + " BEGIN\n" +
                  "  INSERT INTO " + MMS_FTS_TABLE_NAME + "(" + MMS_FTS_TABLE_NAME + ", " + ID + ", " + BODY + ", " + THREAD_ID + ") VALUES('delete', old." + MmsDatabase.ID + ", old." + MmsDatabase.BODY + ", old." + MmsDatabase.THREAD_ID + ");\n" +
                  "END;\n",
          "CREATE TRIGGER mms_au AFTER UPDATE ON " + MmsDatabase.TABLE_NAME + " BEGIN\n" +
                  "  INSERT INTO " + MMS_FTS_TABLE_NAME + "(" + MMS_FTS_TABLE_NAME + ", " + ID + ", " + BODY + ", " + THREAD_ID + ") VALUES('delete', old." + MmsDatabase.ID + ", old." + MmsDatabase.BODY + ", old." + MmsDatabase.THREAD_ID + ");\n" +
                  "  INSERT INTO " + MMS_FTS_TABLE_NAME + "(" + ID + ", " + BODY + ", " + THREAD_ID + ") VALUES (new." + MmsDatabase.ID + ", new." + MmsDatabase.BODY + ", new." + MmsDatabase.THREAD_ID + ");\n" +
                  "END;"
  };

  // Base query definitions with placeholders for blocked contact filtering
  private static final String MESSAGES_QUERY_BASE =
          "SELECT " +
                  ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.ADDRESS + " AS " + CONVERSATION_ADDRESS + ", " +
                  MmsSmsColumns.ADDRESS + " AS " + MESSAGE_ADDRESS + ", " +
                  "snippet(" + SMS_FTS_TABLE_NAME + ", -1, '', '', '...', 7) AS " + SNIPPET + ", " +
                  SmsDatabase.TABLE_NAME + "." + SmsDatabase.DATE_SENT + " AS " + MmsSmsColumns.NORMALIZED_DATE_SENT + ", " +
                  SMS_FTS_TABLE_NAME + "."  + THREAD_ID + " " +
                  "FROM " + SmsDatabase.TABLE_NAME + " " +
                  "INNER JOIN " + SMS_FTS_TABLE_NAME + " ON " + SMS_FTS_TABLE_NAME + "." + ID + " = " + SmsDatabase.TABLE_NAME + "." + SmsDatabase.ID + " " +
                  "INNER JOIN " + ThreadDatabase.TABLE_NAME + " ON " + SMS_FTS_TABLE_NAME + "." + THREAD_ID + " = " + ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.ID + " " +
                  "WHERE " + SMS_FTS_TABLE_NAME + " MATCH ? " +
                  "AND NOT " + MmsSmsColumns.IS_DELETED +
                  " AND NOT " + MmsSmsColumns.IS_GROUP_UPDATE +
                  " %s " + // placeholder for blocked
                  "UNION ALL " +
                  "SELECT " +
                  ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.ADDRESS + " AS " + CONVERSATION_ADDRESS + ", " +
                  MmsSmsColumns.ADDRESS + " AS " + MESSAGE_ADDRESS + ", " +
                  "snippet(" + MMS_FTS_TABLE_NAME + ", -1, '', '', '...', 7) AS " + SNIPPET + ", " +
                  MmsDatabase.TABLE_NAME + "." + MmsDatabase.DATE_SENT + " AS " + MmsSmsColumns.NORMALIZED_DATE_SENT + ", " +
                  MMS_FTS_TABLE_NAME + "." + THREAD_ID + " " +
                  "FROM " + MmsDatabase.TABLE_NAME + " " +
                  "INNER JOIN " + MMS_FTS_TABLE_NAME + " ON " + MMS_FTS_TABLE_NAME + "." + ID + " = " + MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID + " " +
                  "INNER JOIN " + ThreadDatabase.TABLE_NAME + " ON " + MMS_FTS_TABLE_NAME + "." + THREAD_ID + " = " + ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.ID + " " +
                  "WHERE " + MMS_FTS_TABLE_NAME + " MATCH ? " +
                  "AND NOT " + MmsSmsColumns.IS_DELETED +
                  " AND NOT " + MmsSmsColumns.IS_GROUP_UPDATE +
                  " %s " + // placeholder for blocked
                  "ORDER BY " + MmsSmsColumns.NORMALIZED_DATE_SENT + " DESC " +
                  "LIMIT ?";

  private static final String MESSAGES_FOR_THREAD_QUERY_BASE =
          "SELECT " +
                  ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.ADDRESS + " AS " + CONVERSATION_ADDRESS + ", " +
                  MmsSmsColumns.ADDRESS + " AS " + MESSAGE_ADDRESS + ", " +
                  "snippet(" + SMS_FTS_TABLE_NAME + ", -1, '', '', '...', 7) AS " + SNIPPET + ", " +
                  SmsDatabase.TABLE_NAME + "." + SmsDatabase.DATE_SENT + " AS " + MmsSmsColumns.NORMALIZED_DATE_SENT + ", " +
                  SMS_FTS_TABLE_NAME + "." + THREAD_ID + " " +
                  "FROM " + SmsDatabase.TABLE_NAME + " " +
                  "INNER JOIN " + SMS_FTS_TABLE_NAME + " ON " + SMS_FTS_TABLE_NAME + "." + ID + " = " + SmsDatabase.TABLE_NAME + "." + SmsDatabase.ID + " " +
                  "INNER JOIN " + ThreadDatabase.TABLE_NAME + " ON " + SMS_FTS_TABLE_NAME + "." + THREAD_ID + " = " + ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.ID + " " +
                  "WHERE " + SMS_FTS_TABLE_NAME + " MATCH ? AND " + SmsDatabase.TABLE_NAME + "." + MmsSmsColumns.THREAD_ID + " = ? " +
                  "AND NOT " + MmsSmsColumns.IS_DELETED +
                  " AND NOT " + MmsSmsColumns.IS_GROUP_UPDATE +
                  " %s " + // placeholder for blocked
                  "UNION ALL " +
                  "SELECT " +
                  ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.ADDRESS + " AS " + CONVERSATION_ADDRESS + ", " +
                  MmsSmsColumns.ADDRESS + " AS " + MESSAGE_ADDRESS + ", " +
                  "snippet(" + MMS_FTS_TABLE_NAME + ", -1, '', '', '...', 7) AS " + SNIPPET + ", " +
                  MmsDatabase.TABLE_NAME + "." + MmsDatabase.DATE_SENT + " AS " + MmsSmsColumns.NORMALIZED_DATE_SENT + ", " +
                  MMS_FTS_TABLE_NAME + "." + THREAD_ID + " " +
                  "FROM " + MmsDatabase.TABLE_NAME + " " +
                  "INNER JOIN " + MMS_FTS_TABLE_NAME + " ON " + MMS_FTS_TABLE_NAME + "." + ID + " = " + MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID + " " +
                  "INNER JOIN " + ThreadDatabase.TABLE_NAME + " ON " + MMS_FTS_TABLE_NAME + "." + THREAD_ID + " = " + ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.ID + " " +
                  "WHERE " + MMS_FTS_TABLE_NAME + " MATCH ? AND " + MmsDatabase.TABLE_NAME + "." + MmsSmsColumns.THREAD_ID + " = ? " +
                  "AND NOT " + MmsSmsColumns.IS_DELETED +
                  " AND NOT " + MmsSmsColumns.IS_GROUP_UPDATE +
                  " %s " + // placeholder for blocked
                  "ORDER BY " + MmsSmsColumns.NORMALIZED_DATE_SENT + " DESC " +
                  "LIMIT 500";

  public SearchDatabase(@NonNull Context context, @NonNull Provider<SQLCipherOpenHelper> databaseHelper) {
    super(context, databaseHelper);
  }

  public Cursor queryMessages(@NonNull String query, @NonNull Set<String> blockedContacts) {
    SQLiteDatabase db = getReadableDatabase();
    String prefixQuery = adjustQuery(query);
    int queryLimit = Math.min(query.length()*50, 500);

    // Build the blocked contacts filter clause if needed
    String blockedFilter = "";
    if (!blockedContacts.isEmpty()) {
      blockedFilter = " AND " + ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.ADDRESS + " NOT IN (" +
              generatePlaceholders(blockedContacts.size()) + ")";
    }

    // Format the query with the filter placeholders
    String messagesQuery = String.format(MESSAGES_QUERY_BASE, blockedFilter, blockedFilter);

    // Build the query arguments
    List<String> args = new ArrayList<>();
    args.add(prefixQuery); // For SMS query

    // Add blocked contacts for SMS query if any
    if (!blockedContacts.isEmpty()) {
      args.addAll(blockedContacts);
    }

    args.add(prefixQuery); // For MMS query

    // Add blocked contacts for MMS query if any
    if (!blockedContacts.isEmpty()) {
      args.addAll(blockedContacts);
    }

    args.add(String.valueOf(queryLimit));

    Cursor cursor = db.rawQuery(messagesQuery, args.toArray(new String[0]));
    setNotifyConversationListListeners(cursor);
    return cursor;
  }

  public Cursor queryMessages(@NonNull String query, long threadId, @NonNull Set<String> blockedContacts) {
    SQLiteDatabase db = getReadableDatabase();
    String prefixQuery = adjustQuery(query);

    // Build the blocked contacts filter clause if needed
    String blockedFilter = "";
    if (!blockedContacts.isEmpty()) {
      blockedFilter = " AND " + ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.ADDRESS + " NOT IN (" +
              generatePlaceholders(blockedContacts.size()) + ")";
    }

    // Format the query with the filter placeholders
    String messagesForThreadQuery = String.format(MESSAGES_FOR_THREAD_QUERY_BASE, blockedFilter, blockedFilter);

    // Build the query arguments
    List<String> args = new ArrayList<>();
    args.add(prefixQuery);
    args.add(String.valueOf(threadId));

    // Add blocked contacts for SMS query if any
    if (!blockedContacts.isEmpty()) {
      args.addAll(blockedContacts);
    }

    args.add(prefixQuery);
    args.add(String.valueOf(threadId));

    // Add blocked contacts for MMS query if any
    if (!blockedContacts.isEmpty()) {
      args.addAll(blockedContacts);
    }

    Cursor cursor = db.rawQuery(messagesForThreadQuery, args.toArray(new String[0]));
    setNotifyConversationListListeners(cursor);
    return cursor;
  }

  private String adjustQuery(@NonNull String query) {
    List<String> tokens = Stream.of(query.split(" ")).filter(s -> s.trim().length() > 0).toList();
    String       prefixQuery = Util.join(tokens, "* ");

    prefixQuery += "*";

    return prefixQuery;
  }
}