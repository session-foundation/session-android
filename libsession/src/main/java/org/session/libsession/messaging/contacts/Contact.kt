package org.session.libsession.messaging.contacts

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.truncateIdForDisplay

@Parcelize
class Contact(
    val accountID: String,
    /**
     * The URL from which to fetch the contact's profile picture.
     */
    var profilePictureURL: String? = null,
    /**
     * The file name of the contact's profile picture on local storage.
     */
    var profilePictureFileName: String? = null,
    /**
     * The key with which the profile picture is encrypted.
     */
    var profilePictureEncryptionKey: ByteArray? = null,
    /**
     * The ID of the thread associated with this contact.
     */
    var threadID: Long? = null,
    /**
     * The name of the contact. Use this whenever you need the "real", underlying name of a user (e.g. when sending a message).
     */
    var name: String? = null,
    /**
     * The contact's nickname, if the user set one.
     */
    var nickname: String? = null,
): Parcelable {

    constructor(id: String): this(accountID = id)

    /**
     * The name to display in the UI. For local use only.
     */
    fun displayName(context: ContactContext = ContactContext.REGULAR): String = nickname ?: when (context) {
        ContactContext.REGULAR -> name
        // In open groups, where it's more likely that multiple users have the same name,
        // we display a bit of the Account ID after a user's display name for added context.
        ContactContext.OPEN_GROUP -> name?.let { "$it (${truncateIdForDisplay(accountID)})" }
    } ?: truncateIdForDisplay(accountID)

    enum class ContactContext {
        REGULAR, OPEN_GROUP
    }

    fun isValid(): Boolean {
        if (profilePictureURL != null) { return profilePictureEncryptionKey != null }
        if (profilePictureEncryptionKey != null) { return profilePictureURL != null}
        return true
    }

    override fun equals(other: Any?): Boolean {
        return this.accountID == (other as? Contact)?.accountID
    }

    override fun hashCode(): Int {
        return accountID.hashCode()
    }

    override fun toString(): String {
        return nickname ?: name ?: accountID
    }

    companion object {
        fun contextForRecipient(recipient: Recipient): ContactContext {
            return if (recipient.isCommunityRecipient) ContactContext.OPEN_GROUP else ContactContext.REGULAR
        }
    }
}