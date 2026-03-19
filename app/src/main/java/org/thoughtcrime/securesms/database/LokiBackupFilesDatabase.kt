package org.thoughtcrime.securesms.database

@Deprecated("This class/table is no longer used. Kept only for migration purpose")
object LokiBackupFilesDatabase {

    public const val TABLE_NAME = "backup_files"
    private const val COLUMN_ID = "_id"
    private const val COLUMN_URI = "uri"
    private const val COLUMN_FILE_SIZE = "file_size"
    private const val COLUMN_TIMESTAMP = "timestamp"

    const val createTableCommand = """
                    CREATE TABLE $TABLE_NAME (
                    $COLUMN_ID INTEGER PRIMARY KEY, 
                    $COLUMN_URI TEXT NOT NULL, 
                    $COLUMN_FILE_SIZE INTEGER NOT NULL, 
                    $COLUMN_TIMESTAMP INTEGER NOT NULL
                    );
                """
}
