package org.thoughtcrime.securesms.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.collection.LruCache
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.session.libsession.messaging.open_groups.GroupMemberAndRole
import org.session.libsession.messaging.open_groups.GroupMemberRole
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.util.asSequence
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Provider
import kotlin.concurrent.read
import kotlin.concurrent.write

class GroupMemberDatabase(context: Context, helper: Provider<SQLCipherOpenHelper>) : Database(context, helper) {

    companion object {
        const val TABLE_NAME = "group_member"
        const val GROUP_ID = "group_id" // Very specific format for the community address: "serverUrl.room"
        const val PROFILE_ID = "profile_id" // This is the AccountId of the member
        const val ROLE = "role"

        @JvmField
        val CREATE_GROUP_MEMBER_TABLE_COMMAND = """
      CREATE TABLE $TABLE_NAME (
        $GROUP_ID TEXT NOT NULL,
        $PROFILE_ID TEXT NOT NULL,
        $ROLE TEXT NOT NULL,
        PRIMARY KEY ($GROUP_ID, $PROFILE_ID)
      )
    """.trimIndent()

        private fun readGroupMember(cursor: Cursor): GroupMemberAndRole {
            return GroupMemberAndRole(
                memberId = AccountId(cursor.getString(cursor.getColumnIndexOrThrow(PROFILE_ID))),
                role = GroupMemberRole.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(ROLE))),
            )
        }

        // We use a very specific format for group ID in the db so we have no choice but to convert it here.
        private fun Address.Community.toGroupId(): String {
            return "${serverUrl}.${room}"
        }
    }

    private val cacheByGroupId = LruCache<Address.Community, Map<AccountId, GroupMemberRole>>(100)

    private val _changeNotification = MutableSharedFlow<Address.Community>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val changeNotification: SharedFlow<Address.Community> get() = _changeNotification

    fun getGroupMembers(communityAddress: Address.Community): Map<AccountId, GroupMemberRole> {
        // Look at cache first
        cacheByGroupId[communityAddress]?.let { return it }

        // If not in cache, fetch from database
        val map = fetchGroupMembersFromDb(communityAddress)

        // Update cache
        cacheByGroupId.put(communityAddress, map)

        return map
    }

    fun delete(community: Address.Community) {
        writableDatabase.beginTransaction()
        try {
            val groupId = community.toGroupId()
            writableDatabase.delete(TABLE_NAME, "$GROUP_ID = ?", arrayOf(groupId))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }

        // Clear cache
        cacheByGroupId.remove(community)

        _changeNotification.tryEmit(community)
    }

    private fun fetchGroupMembersFromDb(address: Address.Community): Map<AccountId, GroupMemberRole> {
        return readableDatabase.rawQuery("SELECT $PROFILE_ID, $ROLE FROM $TABLE_NAME WHERE $GROUP_ID = ?", address.toGroupId()).use {
            it.asSequence()
                .map(::readGroupMember)
                .associate { m -> m.memberId to m.role }
        }
    }

    fun updateGroupMembers(
        community: Address.Community,
        role: GroupMemberRole,
        memberIDs: Collection<AccountId>
    ) {
        val values = ContentValues(3)

        writableDatabase.beginTransaction()
        val groupId = community.toGroupId()
        try {
            // Clean up the existing members with the same role
            val toDeleteQuery = "$GROUP_ID = ? AND $ROLE = ?"
            val toDeleteArgs = arrayOf(groupId, role.name)

            writableDatabase.delete(TABLE_NAME, toDeleteQuery, toDeleteArgs)

            memberIDs.forEach { memberId ->
                with(values) {
                    put(GROUP_ID, groupId)
                    put(PROFILE_ID, memberId.hexString)
                    put(ROLE, role.name)
                }
                writableDatabase.insertOrUpdate(TABLE_NAME, values, "$GROUP_ID = ? AND $PROFILE_ID = ?", arrayOf(groupId, memberId.hexString))
            }

            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }

        updateCache(community)

        _changeNotification.tryEmit(community)
    }

    private fun updateCache(address: Address.Community) {
        val members = fetchGroupMembersFromDb(address)
        cacheByGroupId.put(address, members)
    }
}