package org.thoughtcrime.securesms.database

@Deprecated("This table/class is no longer used. Kept only for migration purpose")
object LokiUserDatabase {
    // Shared
    private const val displayName = "display_name"

    // Display name cache
    private const val displayNameTable = "loki_user_display_name_database"
    private const val publicKey = "hex_encoded_public_key"
    const val createDisplayNameTableCommand =
        "CREATE TABLE $displayNameTable ($publicKey TEXT PRIMARY KEY, $displayName TEXT);"

    // Server display name cache
    private const val serverDisplayNameTable = "loki_user_server_display_name_database"
    private const val serverID = "server_id"
    const val createServerDisplayNameTableCommand =
        "CREATE TABLE $serverDisplayNameTable ($publicKey TEXT, $serverID TEXT, $displayName TEXT, PRIMARY KEY ($publicKey, $serverID));"
}