package org.thoughtcrime.securesms.database;

/**
 * This class is no longer used. Only kept for migration purpose
 */
@Deprecated(forRemoval = true)
public class PushDatabase  {
  public static final String TABLE_NAME       = "push";
  public static final String ID               = "_id";
  public static final String TYPE             = "type";
  public static final String SOURCE           = "source";
  public static final String DEVICE_ID        = "device_id";
  public static final String LEGACY_MSG       = "body";
  public static final String CONTENT          = "content";
  public static final String TIMESTAMP        = "timestamp";
  public static final String SERVER_TIMESTAMP = "server_timestamp";
  public static final String SERVER_GUID      = "server_guid";

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " (" + ID + " INTEGER PRIMARY KEY, " +
      TYPE + " INTEGER, " + SOURCE + " TEXT, " + DEVICE_ID + " INTEGER, " + LEGACY_MSG + " TEXT, " + CONTENT + " TEXT, " + TIMESTAMP + " INTEGER, " +
      SERVER_TIMESTAMP + " INTEGER DEFAULT 0, " + SERVER_GUID + " TEXT DEFAULT NULL);";
}
