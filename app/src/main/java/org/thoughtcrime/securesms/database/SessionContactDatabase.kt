package org.thoughtcrime.securesms.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import org.json.JSONArray
import org.session.libsession.messaging.contacts.Contact
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import javax.inject.Provider

class SessionContactDatabase(context: Context, helper: Provider<SQLCipherOpenHelper>) : Database(context, helper) {

    companion object {
        const val sessionContactTable = "session_contact_database"
        const val accountID = "session_id"
        const val name = "name"
        const val nickname = "nickname"
        const val profilePictureURL = "profile_picture_url"
        const val profilePictureFileName = "profile_picture_file_name"
        const val profilePictureEncryptionKey = "profile_picture_encryption_key"
        const val threadID = "thread_id"
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
    }

    fun getContactWithAccountID(accountID: String): Contact? {
        val database = readableDatabase
        return database.get(sessionContactTable, "${Companion.accountID} = ?", arrayOf( accountID )) { cursor ->
            contactFromCursor(cursor)
        }
    }

    fun getContacts(accountIDs: Collection<String>): List<Contact> {
        val database = readableDatabase
        return database.getAll(
            sessionContactTable,
            "$accountID IN (SELECT value FROM json_each(?))",
            arrayOf(JSONArray(accountIDs).toString())
        ) { cursor -> contactFromCursor(cursor) }
    }

    fun getAllContacts(): Set<Contact> {
        val database = readableDatabase
        return database.getAll(sessionContactTable, null, null) { cursor ->
            contactFromCursor(cursor)
        }.toSet()
    }

    fun setContactIsTrusted(contact: Contact, isTrusted: Boolean, threadID: Long) {
        val database = writableDatabase
        val contentValues = ContentValues(1)
        contentValues.put(Companion.isTrusted, if (isTrusted) 1 else 0)
        database.update(sessionContactTable, contentValues, "$accountID = ?", arrayOf( contact.accountID ))
        if (threadID >= 0) {
            notifyConversationListeners(threadID)
        }
        notifyConversationListListeners()
    }

    fun setContact(contact: Contact) {
        val database = writableDatabase
        val contentValues = ContentValues(8)
        contentValues.put(accountID, contact.accountID)
        contentValues.put(name, contact.name)
        contentValues.put(nickname, contact.nickname)
        contentValues.put(profilePictureURL, contact.profilePictureURL)
        contentValues.put(profilePictureFileName, contact.profilePictureFileName)
        contact.profilePictureEncryptionKey?.let {
            contentValues.put(profilePictureEncryptionKey, Base64.encodeBytes(it))
        }
        contentValues.put(threadID, contact.threadID)
        database.insertOrUpdate(sessionContactTable, contentValues, "$accountID = ?", arrayOf( contact.accountID ))
        notifyConversationListListeners()
    }

    fun deleteContact(accountId: String) {
        val database = writableDatabase
        val rowsAffected = database.delete(sessionContactTable, "$accountID = ?", arrayOf( accountId ))
        if (rowsAffected == 0) {
            Log.w("SessionContactDatabase", "Failed to delete contact with id: $accountId")
        }
        notifyConversationListListeners()
    }

    fun contactFromCursor(cursor: Cursor): Contact {
        val contact = Contact(cursor.getString(accountID))
        contact.name = cursor.getStringOrNull(name)
        contact.nickname = cursor.getStringOrNull(nickname)
        contact.profilePictureURL = cursor.getStringOrNull(profilePictureURL)
        contact.profilePictureFileName = cursor.getStringOrNull(profilePictureFileName)
        cursor.getStringOrNull(profilePictureEncryptionKey)?.let {
            contact.profilePictureEncryptionKey = Base64.decode(it)
        }
        contact.threadID = cursor.getLong(threadID)
        return contact
    }

    fun queryContactsByName(constraint: String, excludeUserAddresses: Set<String> = emptySet()): Cursor {
        val whereClause = StringBuilder("($name LIKE ? OR $nickname LIKE ?)")
        val whereArgs = ArrayList<String>()
        whereArgs.add("%$constraint%")
        whereArgs.add("%$constraint%")

        // filter out users is the list isn't empty
        if (excludeUserAddresses.isNotEmpty()) {
            whereClause.append(" AND $accountID NOT IN (")
            whereClause.append(excludeUserAddresses.joinToString(", ") { "?" })
            whereClause.append(")")

            whereArgs.addAll(excludeUserAddresses)
        }

        return readableDatabase.query(
            sessionContactTable, null, whereClause.toString(), whereArgs.toTypedArray(),
            null, null, null
        )
    }
}