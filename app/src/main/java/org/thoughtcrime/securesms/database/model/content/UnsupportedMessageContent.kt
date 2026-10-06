package org.thoughtcrime.securesms.database.model.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A placeholder for a message whose type this client doesn't know. The raw message is retained in
 * `unsupported_message`, which points back at this row and is replayed once an update knows the type.
 */
@Serializable
@SerialName(UnsupportedMessageContent.TYPE_NAME)
data object UnsupportedMessageContent : MessageContent {
    const val TYPE_NAME = "unsupported_message"

    // FIXME: Move to Crowdin once the design is settled
    const val PLACEHOLDER_TEXT = "This message can't be displayed. Update Session to view it."
}
