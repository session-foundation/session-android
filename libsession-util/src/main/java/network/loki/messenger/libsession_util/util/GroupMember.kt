package network.loki.messenger.libsession_util.util

import org.session.libsignal.utilities.AccountId
import java.util.EnumSet

/**
 * Represents a member of a group.
 *
 * Note: unlike a read-only data class, this class is mutable and it is not thread-safe
 * in general. You have to synchronize access to it if you are going to use it in multiple threads.
 */
class GroupMember private constructor(@Suppress("CanBeParameter") private val nativePtr: Long) {
    init {
        if (nativePtr == 0L) {
            throw NullPointerException("Native pointer is null")
        }
    }

    external fun setInvited(failed: Boolean = false)
    external fun setAccepted()
    external fun setPromoted()
    external fun setPromotionSent()
    external fun setPromotionFailed()
    external fun setPromotionAccepted()
    external fun setRemoved(alsoRemoveMessages: Boolean)

    private external fun statusInt(): Int
    val status: Status? get() = Status.entries.firstOrNull { it.nativeValue == statusInt() }

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

    val removed: Boolean
        get() = status in EnumSet.of(Status.REMOVED, Status.REMOVED_UNKNOWN, Status.REMOVED_INCLUDING_MESSAGES)

    val isAdminOrBeingPromoted: Boolean
        get() = admin || status in EnumSet.of(Status.PROMOTION_SENT, Status.PROMOTION_ACCEPTED)

    val inviteFailed: Boolean
        get() = status == Status.INVITE_FAILED

    val shouldRemoveMessages: Boolean
        get() = status == Status.REMOVED_INCLUDING_MESSAGES

    enum class Status(val nativeValue: Int) {
        INVITE_UNKNOWN(0),
        INVITE_NOT_SENT(1),
        INVITE_FAILED(2),
        INVITE_SENT(3),
        INVITE_ACCEPTED(4),

        PROMOTION_UNKNOWN(5),
        PROMOTION_NOT_SENT(6),
        PROMOTION_FAILED(7),
        PROMOTION_SENT(8),
        PROMOTION_ACCEPTED(9),

        REMOVED_UNKNOWN(10),
        REMOVED(11),
        REMOVED_INCLUDING_MESSAGES(12);
    }

    override fun toString(): String {
        return "GroupMember(name=$name, admin=$admin, supplement=$supplement, status=$status)"
    }
}