package org.thoughtcrime.securesms.database;

import android.content.ContentValues;
import android.content.Context;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.apache.commons.lang3.StringUtils;
import org.session.libsession.utilities.Address;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;

import java.util.List;

import javax.inject.Provider;

public class GroupReceiptDatabase extends Database {

  public  static final String TABLE_NAME = "group_receipts";

  private static final String ID           = "_id";
  public  static final String MMS_ID       = "mms_id";
  private static final String ADDRESS      = "address";
  private static final String STATUS       = "status";
  private static final String TIMESTAMP    = "timestamp";

  @Deprecated(forRemoval = true)
  private static final String UNIDENTIFIED = "unidentified";

  public static final int STATUS_UNKNOWN     = -1;
  public static final int STATUS_UNDELIVERED = 0;
  public static final int STATUS_DELIVERED   = 1;
  public static final int STATUS_READ        = 2;

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " (" + ID + " INTEGER PRIMARY KEY, "                          +
      MMS_ID + " INTEGER, " + ADDRESS + " TEXT, " + STATUS + " INTEGER, " + TIMESTAMP + " INTEGER, " + UNIDENTIFIED + " INTEGER DEFAULT 0);";

  public static final String[] CREATE_INDEXES = {
      "CREATE INDEX IF NOT EXISTS group_receipt_mms_id_index ON " + TABLE_NAME + " (" + MMS_ID + ");",
  };

  public GroupReceiptDatabase(Context context, Provider<SQLCipherOpenHelper> databaseHelper) {
    super(context, databaseHelper);
  }

  public void insert(List<Address> addresses, long mmsId, int status, long timestamp) {
    SQLiteDatabase db = getWritableDatabase();

    for (Address address : addresses) {
      ContentValues values = new ContentValues(4);
      values.put(MMS_ID, mmsId);
      values.put(ADDRESS, address.toString());
      values.put(STATUS, status);
      values.put(TIMESTAMP, timestamp);

      db.insert(TABLE_NAME, null, values);
    }
  }

  public void update(Address address, long mmsId, int status, long timestamp) {
    SQLiteDatabase db     = getWritableDatabase();
    ContentValues  values = new ContentValues(2);
    values.put(STATUS, status);
    values.put(TIMESTAMP, timestamp);

    db.update(TABLE_NAME, values, MMS_ID + " = ? AND " + ADDRESS + " = ? AND " + STATUS + " < ?",
              new String[] {String.valueOf(mmsId), address.toString(), String.valueOf(status)});
  }

  void deleteRowsForMessages(String[] mmsIds) {
    StringBuilder queryBuilder = new StringBuilder();
    for (int i = 0; i < mmsIds.length; i++) {
      queryBuilder.append(MMS_ID+" = ").append(mmsIds[i]);
      if (i+1 < mmsIds.length) {
        queryBuilder.append(" OR ");
      }
    }
    String idsAsString = queryBuilder.toString();
    SQLiteDatabase db = getWritableDatabase();
    db.delete(TABLE_NAME, idsAsString, null);
  }

  void deleteRowsForMessage(long mmsId) {
    SQLiteDatabase db = getWritableDatabase();
    db.delete(TABLE_NAME, MMS_ID + " = ?", new String[] {String.valueOf(mmsId)});
  }

  void deleteRowsForMessages(long[] mmsIds) {
    SQLiteDatabase db = getWritableDatabase();
    db.delete(TABLE_NAME, MMS_ID + " IN (?)", new String[] {StringUtils.join(mmsIds, ',')});
  }
}
