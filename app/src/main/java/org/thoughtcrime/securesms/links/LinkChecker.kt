package org.thoughtcrime.securesms.links

import javax.inject.Inject
import javax.inject.Singleton

sealed class LinkType(open val url: String) {
    data class GenericLink(
        override val url: String,
    ) : LinkType(url)

    data class CommunityLink(
        override val url: String,
        val joined: Boolean,
        val displayType: DisplayType,
        val name: String = "",
        val allowCopyUrl: Boolean = true
    ) : LinkType(url){
        enum class DisplayType{
            CONVERSATION, ENTERED, SCANNED, GROUP, SEARCH
        }
    }
}

internal fun interface LinkRule {
    suspend fun classify(url: String): LinkType?
}

@Singleton
class LinkChecker internal constructor(
    private val rules: List<LinkRule>,
) {
    @Inject
    constructor(
        communityLinkRule: CommunityLinkRule,
    ) : this(listOf(communityLinkRule))

    suspend fun check(
        url: String
    ): LinkType {
        val normalizedUrl = url.trim()
        for (rule in rules) {
            rule.classify(normalizedUrl)?.let { return it }
        }

        return LinkType.GenericLink(normalizedUrl)
    }
}
