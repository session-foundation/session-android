package org.thoughtcrime.securesms.database

import android.content.Context
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import javax.inject.Provider

@Deprecated("Use the new BlindMappingRepository instead. This class will be removed in a future release.")
class BlindedIdMappingDatabase(context: Context, helper: Provider<SQLCipherOpenHelper>) : Database(context, helper) {

    companion object {
        const val TABLE_NAME = "blinded_id_mapping"
        const val ROW_ID = "_id"
        const val BLINDED_PK = "blinded_pk"
        const val SESSION_PK = "session_pk"
        const val SERVER_URL = "server_url"
        const val SERVER_PK = "server_pk"

        @JvmField
        val CREATE_BLINDED_ID_MAPPING_TABLE_COMMAND = """
      CREATE TABLE $TABLE_NAME (
        $ROW_ID INTEGER PRIMARY KEY,
        $BLINDED_PK TEXT NOT NULL,
        $SESSION_PK TEXT DEFAULT NULL,
        $SERVER_URL TEXT NOT NULL,
        $SERVER_PK TEXT NOT NULL
      )
    """.trimIndent()

    }

}