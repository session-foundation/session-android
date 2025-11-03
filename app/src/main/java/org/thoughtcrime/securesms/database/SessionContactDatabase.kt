package org.thoughtcrime.securesms.database

import android.content.Context
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import javax.inject.Provider

@Deprecated("We no longer store contacts in the database, use the one from config instead")
class SessionContactDatabase(context: Context, helper: Provider<SQLCipherOpenHelper>) : Database(context, helper) {

    companion object {
        const val sessionContactTable = "session_contact_database"

        const val accountID = "session_id"
        private const val name = "name"
        private const val nickname = "nickname"
        private const val profilePictureURL = "profile_picture_url"
        private const val profilePictureFileName = "profile_picture_file_name"
        private const val profilePictureEncryptionKey = "profile_picture_encryption_key"
        private const val threadID = "thread_id"
        const val isTrusted = "is_trusted"

        @JvmStatic val createSessionContactTableCommand =
            "CREATE TABLE $sessionContactTable " +
                "($accountID STRING PRIMARY KEY, " +
                "$name TEXT DEFAULT NULL, " +
                "$nickname TEXT DEFAULT NULL, " +
                "$profilePictureURL TEXT DEFAULT NULL, " +
                "$profilePictureFileName TEXT DEFAULT NULL, " +
                "$profilePictureEncryptionKey BLOB DEFAULT NULL, " +
                "$threadID INTEGER DEFAULT -1, " +
                "$isTrusted INTEGER DEFAULT 0);"

        @JvmStatic
        val dropTableCommand = "DROP TABLE $sessionContactTable"
    }
}