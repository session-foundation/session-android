package org.thoughtcrime.securesms.database

import android.content.Context
import androidx.collection.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import java.util.Optional
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.jvm.optionals.getOrNull

@Singleton
class CommunityDatabase @Inject constructor(
    @ApplicationContext context: Context,
    helper: Provider<SQLCipherOpenHelper>,
    private val json: Json,
) : Database(context, helper) {

    private val cache = LruCache<Address.Community, Optional<OpenGroupApi.RoomInfo>>(24)

    private val mutableChangeNotification = MutableSharedFlow<Address.Community>(
        extraBufferCapacity = 24,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val changeNotification: SharedFlow<Address.Community> get() = mutableChangeNotification

    fun getRoomInfo(address: Address.Community): OpenGroupApi.RoomInfo? {
        val existing = cache[address]

        if (existing != null) {
            return existing.getOrNull()
        }

        return readableDatabase.rawQuery("SELECT $COL_ROOM_INFO FROM $TABLE_NAME WHERE $COL_ADDRESS = ?", address)
            .use { cursor ->
                if (cursor.moveToNext()) {
                    cursor.getString(0)?.let { text -> json.decodeFromString<OpenGroupApi.RoomInfo>(text) }
                } else {
                    null
                }
            }
            .also {
                cache.put(address, Optional.ofNullable(it))
            }
    }


    fun patchRoomInfo(address: Address.Community, jsonText: String) {
        val sql = """
            INSERT OR REPLACE INTO $TABLE_NAME ($COL_ADDRESS, $COL_ROOM_INFO) 
                VALUES (
                    ?, 
                    json_patch(ifnull((SELECT $COL_ROOM_INFO FROM $TABLE_NAME WHERE $COL_ADDRESS = ?), json_object()), ?)
                )
            RETURNING $COL_ROOM_INFO"""

        writableDatabase.rawQuery(sql, address, address, jsonText).use { cursor ->
            check(cursor.moveToNext()) { "Unable to patch room info" }
            json.decodeFromString<OpenGroupApi.RoomInfo>(cursor.getString(0))
        }.also {
            if (cache[address]?.getOrNull() != it) {
                cache.put(address, Optional.of(it))
                mutableChangeNotification.tryEmit(address)
            }
        }
    }

    fun deleteRoomInfo(address: Address.Community) {
        cache.put(address, Optional.empty()) // We know this item doesn't exist in the db which itself is also a cachable information
        writableDatabase.delete(TABLE_NAME, "$COL_ADDRESS = ?", arrayOf(address.address))
        mutableChangeNotification.tryEmit(address)
    }


    companion object {
        private const val TABLE_NAME = "communities"

        private const val COL_ADDRESS = "address"
        private const val COL_ROOM_INFO = "room_info"

        const val MIGRATE_CREATE_TABLE = """
            CREATE TABLE $TABLE_NAME (
                $COL_ADDRESS TEXT NOT NULL PRIMARY KEY COLLATE NOCASE,
                $COL_ROOM_INFO TEXT
            ) WITHOUT ROWID
        """

        val MIGRATE_DROP_OLD_TABLES: Array<String>
            get() = arrayOf(
                "DROP TABLE ${LokiThreadDatabase.publicChatTable}",
                "DROP TABLE ${LokiAPIDatabase.userCountTable}",
            )

        fun migrateFromOldTables(json: Json, db: SQLiteDatabase) {
            val allOpenGroups = db.rawQuery("SELECT ${LokiThreadDatabase.publicChat} FROM ${LokiThreadDatabase.publicChatTable}").use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val og = json.decodeFromString<OpenGroup>(cursor.getString(0))
                        put(Address.Community(og), og)
                    }
                }
            }

            for ((address, og) in allOpenGroups) {
                db.rawExecSQL("""
                    INSERT INTO $TABLE_NAME($COL_ADDRESS, $COL_ROOM_INFO) VALUES (?, 
                        json_object(
                            "token", ?,
                            "write", json(?),
                            "details", json_object(
                                "token", ?,
                                "name", ?,
                                "image_id", ?
                            )
                        )
                    )
                """, address.address,
                    address.room, //token
                    og.canWrite.toString(), //write
                    address.room, //token
                    og.name, //name
                    og.imageId?.toLongOrNull() ?: og.imageId, //image_id
                )
            }
        }
    }
}