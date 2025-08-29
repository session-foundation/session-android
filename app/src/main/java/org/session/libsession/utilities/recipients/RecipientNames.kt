package org.session.libsession.utilities.recipients

import org.session.libsession.utilities.Address
import org.session.libsession.utilities.truncateIdForDisplay
import org.session.libsession.utilities.truncatedForDisplay

/**
 * Retrieve a formatted display name for a recipient.
 *
 * @param attachesBlindedId Whether to append the blinded ID to the display name if the address is a blinded address.
 */
@JvmOverloads
fun Recipient.displayName(
    attachesBlindedId: Boolean = false,
): String {
    val name = when (data) {
        is RecipientData.Self -> data.name
        is RecipientData.Contact -> data.displayName
        is RecipientData.LegacyGroup -> data.name
        is RecipientData.Group -> data.partial.name
        is RecipientData.Generic -> data.displayName
        is RecipientData.Community -> data.roomInfo?.details?.name ?: data.room
        is RecipientData.BlindedContact -> data.displayName
    }

    if (name.isBlank()) {
        val addressToTruncate = when (address) {
            is Address.WithAccountId -> address.accountId.hexString
            is Address.Community -> return address.room // This is last resort - to show the room token
            else -> address.address
        }
        return truncateIdForDisplay(addressToTruncate)
    }

    if (attachesBlindedId && address is Address.Blinded) {
        return "$name (${address.blindedId.truncatedForDisplay()})"
    }

    return name
}