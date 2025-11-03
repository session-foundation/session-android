package org.thoughtcrime.securesms.database

import android.content.Context
import android.database.Cursor
import org.session.libsession.messaging.messages.ExpirationDatabaseMetadata
import org.session.libsession.utilities.GroupUtil.COMMUNITY_INBOX_PREFIX
import org.session.libsession.utilities.GroupUtil.COMMUNITY_PREFIX
import org.session.libsession.utilities.GroupUtil.LEGACY_CLOSED_GROUP_PREFIX
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import javax.inject.Provider

@Deprecated("We no longer store the expiration configuration in the database. ")
class ExpirationConfigurationDatabase(context: Context, helper: Provider<SQLCipherOpenHelper>) : Database(context, helper) {

    companion object {
        private const val TABLE_NAME = "expiration_configuration"
        private const val THREAD_ID = "thread_id"
        private const val UPDATED_TIMESTAMP_MS = "updated_timestamp_ms"

        @JvmField
        val CREATE_EXPIRATION_CONFIGURATION_TABLE_COMMAND = """
          CREATE TABLE $TABLE_NAME (
            $THREAD_ID INTEGER NOT NULL PRIMARY KEY ON CONFLICT REPLACE,
            $UPDATED_TIMESTAMP_MS INTEGER DEFAULT NULL
          )
        """.trimIndent()

        @JvmField
        val MIGRATE_GROUP_CONVERSATION_EXPIRY_TYPE_COMMAND = """
            INSERT INTO $TABLE_NAME ($THREAD_ID) SELECT ${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.ID}
            FROM ${ThreadDatabase.TABLE_NAME}, ${RecipientDatabase.TABLE_NAME}
            WHERE ${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.ADDRESS} LIKE '$LEGACY_CLOSED_GROUP_PREFIX%'
            AND EXISTS (SELECT ${RecipientDatabase.EXPIRE_MESSAGES} FROM ${RecipientDatabase.TABLE_NAME} WHERE ${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.ADDRESS} = ${RecipientDatabase.TABLE_NAME}.${RecipientDatabase.ADDRESS} AND ${RecipientDatabase.EXPIRE_MESSAGES} > 0)
        """.trimIndent()

        @JvmField
        val MIGRATE_ONE_TO_ONE_CONVERSATION_EXPIRY_TYPE_COMMAND = """
            INSERT INTO $TABLE_NAME ($THREAD_ID) SELECT ${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.ID}
            FROM ${ThreadDatabase.TABLE_NAME}, ${RecipientDatabase.TABLE_NAME}
            WHERE ${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.ADDRESS} NOT LIKE '$LEGACY_CLOSED_GROUP_PREFIX%'
            AND ${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.ADDRESS} NOT LIKE '$COMMUNITY_PREFIX%'
            AND ${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.ADDRESS} NOT LIKE '$COMMUNITY_INBOX_PREFIX%'
            AND EXISTS (SELECT ${RecipientDatabase.EXPIRE_MESSAGES} FROM ${RecipientDatabase.TABLE_NAME} WHERE ${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.ADDRESS} = ${RecipientDatabase.TABLE_NAME}.${RecipientDatabase.ADDRESS} AND ${RecipientDatabase.EXPIRE_MESSAGES} > 0)
        """.trimIndent()

        @JvmField
        val DROP_TABLE_COMMAND = "DROP TABLE $TABLE_NAME"
    }
}