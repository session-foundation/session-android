package org.thoughtcrime.securesms.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.collection.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class LokiThreadDatabase @Inject constructor(
    @ApplicationContext context: Context,
    helper: Provider<SQLCipherOpenHelper>,
    private val json: Json,
) : Database(context, helper) {

    companion object {
        private val sessionResetTable = "loki_thread_session_reset_database"
        private val publicChatTable = "loki_public_chat_database"
        val threadID = "thread_id"
        private val sessionResetStatus = "session_reset_status"
        val publicChat = "public_chat"
        @JvmStatic
        val createSessionResetTableCommand = "CREATE TABLE $sessionResetTable ($threadID INTEGER PRIMARY KEY, $sessionResetStatus INTEGER DEFAULT 0);"
        @JvmStatic
        val createPublicChatTableCommand = "CREATE TABLE $publicChatTable ($threadID INTEGER PRIMARY KEY, $publicChat TEXT);"
    }

    private val mutableChangeNotification = MutableSharedFlow<Long>()

    val changeNotification: SharedFlow<Long> get() = mutableChangeNotification

    private val cacheByThreadId = LruCache<Long, OpenGroup>(32)

    fun getAllOpenGroups(): Map<Long, OpenGroup> {
        val database = readableDatabase
        var cursor: Cursor? = null
        val result = mutableMapOf<Long, OpenGroup>()
        try {
            cursor = database.rawQuery("select * from $publicChatTable", null)
            while (cursor != null && cursor.moveToNext()) {
                val threadID = cursor.getLong(threadID)
                val string = cursor.getString(publicChat)
                val openGroup = runCatching { json.decodeFromString<OpenGroup>(string) }
                    .onFailure { Log.d("LokiThreadDatabase", "Error decoding open group json", it) }
                    .getOrNull()
                if (openGroup != null) result[threadID] = openGroup
            }
        } catch (e: Exception) {
            // do nothing
        } finally {
            cursor?.close()
        }

        // Update the cache with the results
        for ((id, group) in result) {
            cacheByThreadId.put(id, group)
        }

        return result
    }

    fun getOpenGroupChat(address: Address.Community): OpenGroup? {
        return readableDatabase.rawQuery("SELECT $publicChat FROM $publicChatTable WHERE $publicChat ->> '$.server' = ? AND $publicChat ->> '$.room' = ?",
            address.serverUrl, address.room).use { cursor ->
                if (cursor.moveToNext()) {
                    cursor.getString(0)?.let { text -> json.decodeFromString<OpenGroup>(text) }
                } else {
                    null
                }
        }
    }

    fun getOpenGroupChat(threadID: Long): OpenGroup? {
        if (threadID < 0) {
            return null
        }

        // Check the cache first
        cacheByThreadId[threadID]?.let {
            return it
        }

        val database = readableDatabase
        return database.get(publicChatTable, "${Companion.threadID} = ?", arrayOf(threadID.toString())) { cursor ->
            runCatching { json.decodeFromString<OpenGroup>(cursor.getString(publicChat)) }
                .onFailure { Log.d("LokiThreadDatabase", "Error decoding open group json", it) }
                .getOrNull()
        }
    }

    fun setOpenGroupChat(openGroup: OpenGroup, threadID: Long) {
        if (threadID < 0) {
            return
        }

        // Check if the group has really changed
        val cache = cacheByThreadId[threadID]
        if (cache == openGroup) {
            return
        } else {
            cacheByThreadId.put(threadID, openGroup)
        }

        val database = writableDatabase
        val contentValues = ContentValues(2)
        contentValues.put(Companion.threadID, threadID)
        contentValues.put(publicChat, json.encodeToString(openGroup))
        database.insertOrUpdate(publicChatTable, contentValues, "${Companion.threadID} = ?", arrayOf(threadID.toString()))
        mutableChangeNotification.tryEmit(threadID)
    }

    fun removeOpenGroupChat(threadID: Long) {
        if (threadID < 0) return

        val database = writableDatabase
        database.delete(publicChatTable,"${Companion.threadID} = ?", arrayOf(threadID.toString()))

        cacheByThreadId.remove(threadID)
        mutableChangeNotification.tryEmit(threadID)
    }

}