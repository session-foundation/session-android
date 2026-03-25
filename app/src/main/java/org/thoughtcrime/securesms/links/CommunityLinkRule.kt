package org.thoughtcrime.securesms.links

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.CommunityUrlParser
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.withUserConfigs
import org.thoughtcrime.securesms.database.CommunityDatabase
import javax.inject.Inject

class CommunityLinkRule @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val communityDatabase: CommunityDatabase,
) : LinkRule {

    override suspend fun classify(url: String): LinkType? = withContext(Dispatchers.IO) {
        val communityInfo = try {
            CommunityUrlParser.parse(url)
        } catch (_: CommunityUrlParser.Error) {
            return@withContext null
        }

        val joinedCommunity = configFactory.withUserConfigs {
            it.userGroups.getCommunityInfo(communityInfo.baseUrl, communityInfo.room)
        }
        val roomInfo = communityDatabase.getRoomInfo(Address.Community(communityInfo.baseUrl, communityInfo.room))
        val name = roomInfo?.details?.name
            ?.takeIf { it.isNotBlank() }
            ?: communityInfo.room

        return@withContext LinkType.CommunityLink(
            url = url,
            name = name,
            joined = joinedCommunity != null,
            displayType = LinkType.CommunityLink.DisplayType.CONVERSATION
        )
    }
}
