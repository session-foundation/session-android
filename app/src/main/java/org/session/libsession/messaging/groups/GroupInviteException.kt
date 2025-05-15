package org.session.libsession.messaging.groups

import android.content.Context
import com.squareup.phrase.Phrase
import network.loki.messenger.R
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.StringSubstitutionConstants.COUNT_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.OTHER_NAME_KEY
import org.session.libsession.utilities.UsernameUtils
import org.session.libsession.utilities.truncateIdForDisplay

/**
 * Exception that occurs during a group invite.
 *
 * @param isPromotion Whether the invite was a promotion.
 * @param inviteeAccountIds The account IDs of the invitees that failed.
 * @param groupName The name of the group.
 * @param underlying The underlying exception.
 */
class GroupInviteException(
    val isPromotion: Boolean,
    val inviteeAccountIds: List<String>,
    val groupName: String,
    underlying: Throwable
) : RuntimeException(underlying) {
    init {
        check(inviteeAccountIds.isNotEmpty()) {
            "Can not fail a group invite if there are no invitees"
        }
    }

    fun format(context: Context, usernameUtils: UsernameUtils): CharSequence {
        val getInviteeName = { accountId: String ->
            usernameUtils.getContactNameWithAccountID(accountId)
        }

        val first = inviteeAccountIds.first().let(getInviteeName)
        val second = inviteeAccountIds.getOrNull(1)?.let(getInviteeName)
        val third = inviteeAccountIds.getOrNull(2)?.let(getInviteeName)

        if (second != null && third != null) {
            return Phrase.from(context, if (isPromotion) R.string.adminPromotionFailedDescriptionMultiple else R.string.groupInviteFailedMultiple)
                .put(NAME_KEY, first)
                .put(COUNT_KEY, inviteeAccountIds.size - 1)
                .put(GROUP_NAME_KEY, groupName)
                .format()
        } else if (second != null) {
            return Phrase.from(context, if (isPromotion) R.string.adminPromotionFailedDescriptionTwo else R.string.groupInviteFailedTwo)
                .put(NAME_KEY, first)
                .put(OTHER_NAME_KEY, second)
                .put(GROUP_NAME_KEY, groupName)
                .format()
        } else {
            return Phrase.from(context, if (isPromotion) R.string.adminPromotionFailedDescription else R.string.groupInviteFailedUser)
                .put(NAME_KEY, first)
                .put(GROUP_NAME_KEY, groupName)
                .format()
        }
    }
}
