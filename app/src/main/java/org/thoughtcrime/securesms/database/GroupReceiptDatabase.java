package org.thoughtcrime.securesms.database;

/**
 * This table/class is no longer used. Kept only for migration purpose
 */
@Deprecated(forRemoval = true)
public class GroupReceiptDatabase {

  private  static final String TABLE_NAME = "group_receipts";

  private static final String ID           = "_id";
  public  static final String MMS_ID       = "mms_id";
  private static final String ADDRESS      = "address";
  private static final String STATUS       = "status";
  private static final String TIMESTAMP    = "timestamp";

  @Deprecated(forRemoval = true)
  private static final String UNIDENTIFIED = "unidentified";

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " (" + ID + " INTEGER PRIMARY KEY, "                          +
      MMS_ID + " INTEGER, " + ADDRESS + " TEXT, " + STATUS + " INTEGER, " + TIMESTAMP + " INTEGER, " + UNIDENTIFIED + " INTEGER DEFAULT 0);";

  public static final String[] CREATE_INDEXES = {
      "CREATE INDEX IF NOT EXISTS group_receipt_mms_id_index ON " + TABLE_NAME + " (" + MMS_ID + ");",
  };
}
