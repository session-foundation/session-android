package network.loki.messenger.libsession_util.util

import org.session.libsignal.utilities.AccountId
import java.util.EnumSet

/**
 * Represents a member of a group.
 *
 * Note: unlike a read-only data class, this class is mutable and it is not thread-safe
 * in general. You have to synchronize access to it if you are going to use it in multiple threads.
 */
class GroupMember private constructor(
    // Constructed and used by native code.
    @Suppress("CanBeParameter") private val nativePtr: Long
) {
    init {
        if (nativePtr == 0L) {
            throw NullPointerException("Native pointer is null")
        }
    }

    external fun setInvited()
    external fun setInviteSent()
    external fun setInviteFailed()
    external fun setInviteAccepted()

    external fun setPromoted()
    external fun setPromotionSent()
    external fun setPromotionFailed()
    external fun setPromotionAccepted()

    external fun setRemoved(alsoRemoveMessages: Boolean)

    external fun profilePic(): UserPic?
    external fun setProfilePic(pic: UserPic)

    external fun setName(name: String)

    private external fun nameString(): String
    val name: String get() = nameString()

    private external fun isAdmin(): Boolean
    val admin: Boolean get() = isAdmin()

    private external fun isSupplement(): Boolean
    external fun setSupplement(supplement: Boolean)
    val supplement: Boolean get() = isSupplement()

    external fun accountIdString(): String
    val accountId: AccountId get() = AccountId(accountIdString())

    // The destruction of the native object is called by the GC
    // Ideally we want to expose as Closable, however given the tiny footprint of the native object,
    // it's perfectly ok to let the GC handle it.
    private external fun destroy()
    protected fun finalize() {
        destroy()
    }

    fun isRemoved(status: Status): Boolean {
        return status in EnumSet.of(Status.REMOVED, Status.REMOVED_UNKNOWN, Status.REMOVED_INCLUDING_MESSAGES)
    }

    fun isAdminOrBeingPromoted(status: Status): Boolean {
        return admin || status in EnumSet.of(Status.PROMOTION_SENT, Status.PROMOTION_ACCEPTED)
    }

    fun inviteFailed(status: Status): Boolean {
        return status == Status.INVITE_FAILED
    }

    fun shouldRemoveMessages(status: Status): Boolean {
        return status == Status.REMOVED_INCLUDING_MESSAGES
    }

    enum class Status(val nativeValue: Int) {
        INVITE_UNKNOWN(0),
        INVITE_NOT_SENT(1),
        INVENT_SENDING(2),
        INVITE_FAILED(3),
        INVITE_SENT(4),
        INVITE_ACCEPTED(5),

        PROMOTION_UNKNOWN(6),
        PROMOTION_NOT_SENT(7),
        PROMOTION_SENDING(8),
        PROMOTION_FAILED(9),
        PROMOTION_SENT(10),
        PROMOTION_ACCEPTED(11),

        REMOVED_UNKNOWN(12),
        REMOVED(13),
        REMOVED_INCLUDING_MESSAGES(14);
    }

    override fun toString(): String {
        return "GroupMember(name=$name, admin=$admin, supplement=$supplement)"
    }
}