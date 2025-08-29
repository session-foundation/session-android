package org.thoughtcrime.securesms.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.collection.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.ProStatus
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.database.model.NotifyType
import org.thoughtcrime.securesms.database.model.RecipientSettings
import org.thoughtcrime.securesms.util.DateUtils.Companion.millsToInstant
import org.thoughtcrime.securesms.util.asSequence
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class RecipientSettingsDatabase @Inject constructor(
    @ApplicationContext context: Context,
    databaseHelper: Provider<SQLCipherOpenHelper>,
    private val json: Provider<Json>,
) : Database(context, databaseHelper) {
    private val mutableChangeNotification = MutableSharedFlow<Address>(extraBufferCapacity = 256)
    private val cache = LruCache<Address, RecipientSettings>(256)

    val changeNotification: SharedFlow<Address>
        get() = mutableChangeNotification

    fun save(address: Address, updater: (RecipientSettings) -> RecipientSettings) {
        val oldSettings = getSettings(address)
        val newSettings = updater.invoke(oldSettings)

        // If nothing is updated, return early
        if (oldSettings == newSettings) {
            Log.d(TAG, "No changes to settings for ${address.debugString}, old: $oldSettings, new: $newSettings")
            return
        }

        // Otherwise update the database and cache
        Log.d(TAG, "Saving settings to db for ${address.debugString}")
        cache.put(address, newSettings)
        writableDatabase.insertWithOnConflict(
            TABLE_NAME,
            null,
            newSettings.toContentValues().apply {
                put(COL_ADDRESS, address.toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )

        mutableChangeNotification.tryEmit(address)
    }

    /**
     * This method finds recipients by their profile picture URL.
     * This is currently only useful for migrating old profile pictures purposes, and shall
     * be removed in the future.
     */
    fun findRecipientsForProfilePic(profilePicUrl: String): Set<Address> {
        return readableDatabase.rawQuery(
            "SELECT DISTINCT $COL_ADDRESS FROM $TABLE_NAME WHERE $COL_PROFILE_PIC_URL = ?",
            profilePicUrl
        ).use { cursor ->
            cursor.asSequence()
                .mapTo(hashSetOf()) { Address.fromSerialized(cursor.getString(0)) }
        }
    }

    fun delete(address: Address) {
        cache.remove(address)
        if (writableDatabase.delete(
            TABLE_NAME,
            "$COL_ADDRESS = ?",
            arrayOf(address.toString())
        ) > 0) {
            mutableChangeNotification.tryEmit(address)
        }
    }

    fun getSettings(address: Address): RecipientSettings {
        val existing = cache[address]
        if (existing != null) {
            return existing
        }

        return readableDatabase.rawQuery("SELECT * FROM $TABLE_NAME WHERE $COL_ADDRESS = ?", address.address)
            .use { cursor ->
                // If no settings are saved in the database, return the empty settings, and cache
                // that as well so that we don't have to query the database again.
                val settings = if (cursor.moveToNext()) {
                    cursor.toRecipientSettings()
                } else {
                    RecipientSettings()
                }

                cache.put(address, settings)
                settings
            }
    }

    private fun Cursor.toRecipientSettings(): RecipientSettings {
        return RecipientSettings(
            muteUntil = getLong(getColumnIndexOrThrow(COL_MUTE_UNTIL)).millsToInstant(),
            notifyType = readNotifyType(getString(getColumnIndexOrThrow(COL_NOTIFY_TYPE))),
            autoDownloadAttachments = getInt(getColumnIndexOrThrow(COL_AUTO_DOWNLOAD_ATTACHMENTS)) == 1,
            profilePic = readUserProfile(
                keyB64 = getString(getColumnIndexOrThrow(COL_PROFILE_PIC_KEY)),
                url = getString(getColumnIndexOrThrow(COL_PROFILE_PIC_URL))
            ),
            blocksCommunityMessagesRequests = getInt(getColumnIndexOrThrow(COL_BLOCKS_COMMUNITY_MESSAGES_REQUESTS)) == 1,
            name = getString(getColumnIndexOrThrow(COL_NAME)),
            proStatus = getString(getColumnIndexOrThrow(COL_PRO_STATUS))
                ?.let {
                    runCatching {
                        json.get().decodeFromString<ProStatus>(it)
                    }.getOrNull()
                }
                ?: ProStatus.None,
            profileUpdated = getLong(getColumnIndexOrThrow(COL_PROFILE_UPDATE_TIME)).millsToInstant(),
        )
    }

    private fun RecipientSettings.toContentValues(): ContentValues {
        return ContentValues().apply {
            put(COL_NAME, name)
            put(COL_MUTE_UNTIL, muteUntil?.toEpochMilli() ?: 0L)
            put(COL_NOTIFY_TYPE, notifyType.name)
            put(COL_AUTO_DOWNLOAD_ATTACHMENTS, autoDownloadAttachments)
            put(COL_PROFILE_PIC_KEY, profilePic?.key?.data?.let(Base64::encodeBytes))
            put(COL_PROFILE_PIC_URL, profilePic?.url)
            put(COL_BLOCKS_COMMUNITY_MESSAGES_REQUESTS, blocksCommunityMessagesRequests)
            put(COL_PRO_STATUS, json.get().encodeToString(proStatus))
            put(COL_PROFILE_UPDATE_TIME, profileUpdated?.toEpochMilli() ?: 0L)
        }
    }

    /**
     * This method returns all profile pic url and key.
     * This will be used to identify which avatars are still being used  to exclude
     * them from the cleanup.
     */
    fun getAllReferencedAvatarFiles(): Set<RemoteFile.Encrypted> {
        val recipientAvatars = HashSet<RemoteFile.Encrypted>()
        readableDatabase.rawQuery(
            """
        SELECT DISTINCT $COL_PROFILE_PIC_URL, $COL_PROFILE_PIC_KEY
        FROM $TABLE_NAME
        WHERE $COL_PROFILE_PIC_URL IS NOT NULL AND $COL_PROFILE_PIC_URL != ''
          AND $COL_PROFILE_PIC_KEY IS NOT NULL AND $COL_PROFILE_PIC_KEY != ''
        """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val url = cursor.getString(0)
                val keyB64 = cursor.getString(1)
                runCatching {
                    val keyBytes = Base64.decode(keyB64)
                    recipientAvatars += RemoteFile.Encrypted(url = url, key = Bytes(keyBytes))
                }.onFailure {
                    // ignore bad rows
                }
            }
        }
        return recipientAvatars
    }

    fun getAllRecipientAddresses(): Set<Address> {
        return readableDatabase.rawQuery(
            "SELECT $COL_ADDRESS FROM $TABLE_NAME"
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    val raw = cursor.getString(0)
                    if (!raw.isNullOrBlank()) add(Address.fromSerialized(raw))
                }
            }
        }
    }

    /**
     * Delete all rows whose address is NOT in [addressesToKeep].
     * Returns the number of rows deleted.
     */
    fun cleanupRecipientSettings(addressesToKeep: Set<Address>): Int {
        if (addressesToKeep.isEmpty()) return 0

        // Collect all rows, figure out orphans in memory
        val allRecipientAddresses = getAllRecipientAddresses()
        val orphans = allRecipientAddresses.filter { it !in addressesToKeep }

        if (orphans.isEmpty()) return 0

        var deleted = 0
        val database = writableDatabase
        database.beginTransaction()
        try {
            for (address in orphans) {
                val rows = database.delete(
                    TABLE_NAME,
                    "$COL_ADDRESS = ?",
                    arrayOf(address.toString())
                )
                if (rows > 0) {
                    deleted += rows
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }

        // Notify after db transaction
        for (address in orphans) {
            cache.remove(address)
            mutableChangeNotification.tryEmit(address)
        }

        return deleted
    }

    companion object {
        private const val TAG = "RecipientSettingsDatabase"

        const val TABLE_NAME = "recipient_settings"

        const val COL_ADDRESS = "address"
        private const val COL_MUTE_UNTIL = "mute_until"
        private const val COL_NOTIFY_TYPE = "notify_type"
        private const val COL_AUTO_DOWNLOAD_ATTACHMENTS = "auto_download_attachments"
        private const val COL_PROFILE_PIC_KEY = "profile_pic_key_b64"
        private const val COL_PROFILE_PIC_URL = "profile_pic_url"
        private const val COL_NAME = "name"
        private const val COL_BLOCKS_COMMUNITY_MESSAGES_REQUESTS = "blocks_community_messages_requests"
        private const val COL_PRO_STATUS = "pro_status"

        // The time when the profile pic/name/is_pro was last updated, in epoch seconds.
        private const val COL_PROFILE_UPDATE_TIME = "profile_update_time"

        val MIGRATION_CREATE_TABLE = arrayOf("""
            CREATE TABLE recipient_settings (
                $COL_ADDRESS TEXT NOT NULL PRIMARY KEY COLLATE NOCASE,
                $COL_MUTE_UNTIL INTEGER NOT NULL DEFAULT 0,
                $COL_NOTIFY_TYPE INTEGER NOT NULL DEFAULT 1,
                $COL_AUTO_DOWNLOAD_ATTACHMENTS BOOLEAN NOT NULL DEFAULT FALSE,
                $COL_PROFILE_PIC_KEY TEXT,
                $COL_PROFILE_PIC_URL TEXT,
                $COL_NAME TEXT,
                $COL_BLOCKS_COMMUNITY_MESSAGES_REQUESTS BOOLEAN NOT NULL DEFAULT TRUE,
                $COL_PRO_STATUS TEXT DEFAULT NULL,
                $COL_PROFILE_UPDATE_TIME INTEGER NOT NULL DEFAULT 0
            ) WITHOUT ROWID
        """,
            "CREATE INDEX recipient_settings_profile_pic ON recipient_settings ($COL_PROFILE_PIC_URL)",
        )

        const val MIGRATE_MOVE_DATA_FROM_OLD_TABLE = """
           INSERT OR REPLACE INTO recipient_settings (
                $COL_ADDRESS,
                $COL_NAME,
                $COL_MUTE_UNTIL,
                $COL_NOTIFY_TYPE,
                $COL_AUTO_DOWNLOAD_ATTACHMENTS,
                $COL_PROFILE_PIC_KEY,
                $COL_PROFILE_PIC_URL,
                $COL_BLOCKS_COMMUNITY_MESSAGES_REQUESTS
            )
            SELECT
                r.recipient_ids,
                ifnull(nullif(r.system_display_name, ''), r.signal_profile_name) AS name,
                r.mute_until,
                CASE(r.notify_type)
                    WHEN 2 THEN "NONE"
                    WHEN 1 THEN "MENTIONS"
                    ELSE "ALL"
                END AS notify_type,
                (IFNULL(r.auto_download, 0) == 1) AS auto_download_attachments,
                r.profile_key,
                r.signal_profile_avatar,
                r.blocks_community_message_requests
            FROM recipient_preferences r
        """


        const val MIGRATE_DROP_OLD_TABLE = """
            DROP TABLE recipient_preferences
        """

        private fun readUserProfile(keyB64: String?, url: String?): UserPic? {
            return if (keyB64.isNullOrBlank() || url.isNullOrEmpty()) {
                null
            } else {
                UserPic(url, Base64.decode(keyB64))
            }
        }

        private fun readNotifyType(t: String): NotifyType {
            return runCatching { NotifyType.valueOf(t) }
                .onFailure { Log.e(TAG, "Error reading notify type of $t", it) }
                .getOrDefault(NotifyType.ALL)
        }


    }
}