package org.thoughtcrime.securesms.database.model

import kotlinx.serialization.Serializable

/**
 * Ties together an emoji with it's associated search tags.
 */
@Serializable
class EmojiSearchData(
    val emoji: String,
    val tags: List<String>,
)
