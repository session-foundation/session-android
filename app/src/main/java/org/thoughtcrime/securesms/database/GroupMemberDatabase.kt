package org.thoughtcrime.securesms.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.collection.LruCache
import org.json.JSONArray
import org.session.libsession.messaging.open_groups.GroupMember
import org.session.libsession.messaging.open_groups.GroupMemberRole
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.util.asSequence
import java.util.EnumSet
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Provider
import kotlin.concurrent.read
import kotlin.concurrent.write

class GroupMemberDatabase(context: Context, helper: Provider<SQLCipherOpenHelper>) : Database(context, helper) {

    companion object {
        const val TABLE_NAME = "group_member"
        const val GROUP_ID = "group_id"
        const val PROFILE_ID = "profile_id"
        const val ROLE = "role"

        private val allColumns = arrayOf(GROUP_ID, PROFILE_ID, ROLE)

        @JvmField
        val CREATE_GROUP_MEMBER_TABLE_COMMAND = """
      CREATE TABLE $TABLE_NAME (
        $GROUP_ID TEXT NOT NULL,
        $PROFILE_ID TEXT NOT NULL,
        $ROLE TEXT NOT NULL,
        PRIMARY KEY ($GROUP_ID, $PROFILE_ID)
      )
    """.trimIndent()

        private fun readGroupMember(cursor: Cursor): GroupMember {
            return GroupMember(
                groupId = cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID)),
                profileId = cursor.getString(cursor.getColumnIndexOrThrow(PROFILE_ID)),
                role = GroupMemberRole.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(ROLE))),
            )
        }
    }

    private val cacheByGroupId = LruCache<String, Map<String, GroupMemberRole>>(100)
    private val cacheLock = ReentrantReadWriteLock()

    fun getGroupMemberRole(groupId: String, profileId: String): GroupMemberRole? {
        // Check cache first
        cacheLock.read {
            cacheByGroupId[groupId]?.let { members ->
                return members[profileId]
            }
        }

        val query = "$GROUP_ID = ? AND $PROFILE_ID = ?"
        val args = arrayOf(groupId, profileId)

        readableDatabase.query(TABLE_NAME, allColumns, query, args, null, null, null).use { cursor ->
            if (cursor.moveToNext()) {
                return readGroupMember(cursor).role
            }
        }

        return null
    }

    fun getGroupMembersRoles(groupId: String, memberIDs: Collection<String>): Map<String, GroupMemberRole> {
        // Check cache first
        cacheLock.read {
            cacheByGroupId[groupId]?.let { members ->
                return members.filterKeys { it in memberIDs }
            }
        }

        val sql = """
            SELECT * FROM $TABLE_NAME
            WHERE $GROUP_ID = ? AND $PROFILE_ID IN (SELECT value FROM json_each(?))
        """.trimIndent()

        return readableDatabase.rawQuery(sql, groupId, JSONArray(memberIDs).toString()).use { cursor ->
            cursor.asSequence()
                .map { readGroupMember(it) }
                .associate { it.profileId to it.role }
        }
    }

    fun getGroupMembersRoles(groupId: String): Map<String, GroupMemberRole> {
        // Check cache first
        cacheLock.read {
            cacheByGroupId[groupId]?.let { members ->
                return members
            }
        }

        val members = fetchGroupMembersFromDb(groupId)

        // Update cache
        cacheLock.write {
            cacheByGroupId.put(groupId, members)
        }

        return members
    }

    private fun fetchGroupMembersFromDb(groupId: String): Map<String, GroupMemberRole> {
        return readableDatabase.query("SELECT $PROFILE_ID, $ROLE FROM $TABLE_NAME WHERE $GROUP_ID = ?", arrayOf(groupId)).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val profileId = cursor.getString(cursor.getColumnIndexOrThrow(PROFILE_ID))
                    val role =
                        GroupMemberRole.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(ROLE)))
                    put(profileId, role)
                }
            }
        }
    }

    fun updateGroupMembers(
        groupId: String,
        role: GroupMemberRole,
        memberIDs: Collection<String>
    ) {
        val values = ContentValues(3)

        writableDatabase.beginTransaction()
        try {
            val toDeleteQuery = "$GROUP_ID = ? AND $ROLE = ?"
            val toDeleteArgs = arrayOf(groupId, role.name)

            writableDatabase.delete(TABLE_NAME, toDeleteQuery, toDeleteArgs)

            memberIDs.forEach { memberId ->
                with(values) {
                    put(GROUP_ID, groupId)
                    put(PROFILE_ID, memberId)
                    put(ROLE, role.name)
                }
                writableDatabase.insertOrUpdate(TABLE_NAME, values, "$GROUP_ID = ? AND $PROFILE_ID = ?", arrayOf(groupId, memberId))
            }

            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }

        updateCache(groupId)
    }

    private fun updateCache(groupId: String) {
        val members = fetchGroupMembersFromDb(groupId)
        cacheLock.write {
            cacheByGroupId.put(groupId, members)
        }
    }
}