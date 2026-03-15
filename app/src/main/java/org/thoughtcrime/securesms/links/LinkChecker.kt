package org.thoughtcrime.securesms.links

import androidx.compose.runtime.Composable
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
        val name: String = ""
    ) : LinkType(url){
        enum class DisplayType{
            CONVERSATION, ENTERED, SCANNED, GROUP
        }
    }
}

internal fun interface LinkRule {
    fun classify(url: String): LinkType?
}

@Singleton
class LinkChecker internal constructor(
    private val rules: List<LinkRule>,
) {
    @Inject
    constructor(
        communityLinkRule: CommunityLinkRule,
    ) : this(listOf(communityLinkRule))

    fun check(
        url: String
    ): LinkType {
        val normalizedUrl = url.trim()
        return rules.firstNotNullOfOrNull { it.classify(normalizedUrl) }
            ?: LinkType.GenericLink(normalizedUrl)
    }
}
