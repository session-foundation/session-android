package org.thoughtcrime.securesms.database

@Deprecated("Exist only for migration purposes")
class LokiThreadDatabase {

    companion object {
        private val sessionResetTable = "loki_thread_session_reset_database"
        val publicChatTable = "loki_public_chat_database"
        val threadID = "thread_id"
        private val sessionResetStatus = "session_reset_status"
        val publicChat = "public_chat"
        @JvmStatic
        val createSessionResetTableCommand = "CREATE TABLE $sessionResetTable ($threadID INTEGER PRIMARY KEY, $sessionResetStatus INTEGER DEFAULT 0);"
        @JvmStatic
        val createPublicChatTableCommand = "CREATE TABLE $publicChatTable ($threadID INTEGER PRIMARY KEY, $publicChat TEXT);"
    }

}