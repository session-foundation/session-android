package org.thoughtcrime.securesms.links

import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.OpenGroupUrlParser
import org.session.libsession.utilities.withUserConfigs
import org.thoughtcrime.securesms.database.CommunityDatabase
import javax.inject.Inject

class CommunityLinkRule @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val communityDatabase: CommunityDatabase,
) : LinkRule {

    override fun classify(url: String): LinkType? {
        val openGroup = try {
            OpenGroupUrlParser.parseUrl(url)
        } catch (_: OpenGroupUrlParser.Error) {
            return null
        }

        val joinedCommunity = configFactory.withUserConfigs {
            it.userGroups.getCommunityInfo(openGroup.server, openGroup.room)
        }
        val roomInfo = communityDatabase.getRoomInfo(Address.Community(openGroup.server, openGroup.room))
        val name = roomInfo?.details?.name
            ?.takeIf { it.isNotBlank() }
            ?: openGroup.room

        return LinkType.CommunityLink(
            url = url,
            name = name,
            joined = joinedCommunity != null,
        )
    }
}
