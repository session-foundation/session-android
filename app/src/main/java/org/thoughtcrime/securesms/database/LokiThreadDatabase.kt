package org.thoughtcrime.securesms.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.collection.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsignal.utilities.JsonUtil
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class LokiThreadDatabase @Inject constructor(
    @ApplicationContext context: Context,
    helper: Provider<SQLCipherOpenHelper>
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
                val openGroup = OpenGroup.fromJSON(string)
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
            val json = cursor.getString(publicChat)
            OpenGroup.fromJSON(json)
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
        contentValues.put(publicChat, JsonUtil.toJson(openGroup.toJson()))
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

    /**
     * Retrieves the OpenGroup for the given threadID and observes changes to it.
     *
     * If in the beginning the OpenGroup is not available, it returns null.
     * If in the middle of the flow the OpenGroup is deleted, it will stop emitting updates.
     */
    fun retrieveAndObserveOpenGroup(scope: CoroutineScope, threadID: Long): StateFlow<OpenGroup>? {
        val initialOpenGroup = getOpenGroupChat(threadID) ?: return null

        return mutableChangeNotification
            .filter { it == threadID }
            .map { getOpenGroupChat(threadID)  }
            .takeWhile { it != null }
            .filterNotNull()
            .stateIn(scope, SharingStarted.Eagerly, initialOpenGroup)
    }

}