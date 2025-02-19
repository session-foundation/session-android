package network.loki.messenger.libsession_util.util

import org.session.libsignal.utilities.AccountId

sealed class GroupInfo {

    data class CommunityGroupInfo(val community: BaseCommunityInfo, val priority: Long) : GroupInfo()

    data class ClosedGroupInfo(
        val groupAccountId: AccountId,
        val adminKey: ByteArray?,
        val authData: ByteArray?,
        val priority: Long,
        val invited: Boolean,
        val name: String,
        val kicked: Boolean,
        val destroyed: Boolean,
        val joinedAtSecs: Long
    ): GroupInfo() {
        init {
            require(adminKey == null || adminKey.isNotEmpty()) {
                "Admin key must be non-empty if present"
            }

            require(authData == null || authData.isNotEmpty()) {
                "Auth data must be non-empty if present"
            }
        }

        fun hasAdminKey() = adminKey != null

        val shouldPoll: Boolean
            get() = !invited && !kicked && !destroyed

        companion object {
            /**
             * Generate the group's admin key(64 bytes) from seed (32 bytes, normally used
             * in group promotions).
             *
             * Use of JvmStatic makes the JNI signature less esoteric.
             */
            @JvmStatic
            external fun adminKeyFromSeed(seed: ByteArray): ByteArray
        }
    }

    data class LegacyGroupInfo(
        val accountId: String,
        val name: String,
        val members: Map<String, Boolean>,
        val encPubKey: ByteArray,
        val encSecKey: ByteArray,
        val priority: Long,
        val disappearingTimer: Long,
        val joinedAtSecs: Long
    ): GroupInfo() {
        companion object {
            @Suppress("FunctionName")
            external fun NAME_MAX_LENGTH(): Int
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as LegacyGroupInfo

            if (accountId != other.accountId) return false
            if (name != other.name) return false
            if (members != other.members) return false
            if (!encPubKey.contentEquals(other.encPubKey)) return false
            if (!encSecKey.contentEquals(other.encSecKey)) return false
            if (priority != other.priority) return false
            if (disappearingTimer != other.disappearingTimer) return false
            if (joinedAtSecs != other.joinedAtSecs) return false

            return true
        }

        override fun hashCode(): Int {
            var result = accountId.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + members.hashCode()
            result = 31 * result + encPubKey.contentHashCode()
            result = 31 * result + encSecKey.contentHashCode()
            result = 31 * result + priority.hashCode()
            result = 31 * result + disappearingTimer.hashCode()
            result = 31 * result + joinedAtSecs.hashCode()
            return result
        }

    }

}