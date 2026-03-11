package org.thoughtcrime.securesms.links

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import network.loki.messenger.libsession_util.ReadableUserGroupsConfig
import network.loki.messenger.libsession_util.util.GroupInfo
import org.junit.Test
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.UserConfigs
import org.thoughtcrime.securesms.database.CommunityDatabase

class LinkCheckerTest {

    @Test
    fun `returns generic link when no rule matches`() {
        val checker = LinkChecker(rules = emptyList())

        assertThat(checker.check(" https://getsession.org ")).isEqualTo(
            LinkType.GenericLink("https://getsession.org")
        )
    }

    @Test
    fun `returns generic link for a regular url`() {
        val checker = checker(
            joinedCommunity = null,
            roomInfo = null,
        )

        assertThat(checker.check("https://getsession.org")).isEqualTo(
            LinkType.GenericLink("https://getsession.org")
        )
    }

    @Test
    fun `detects community links and falls back to room token when no local name exists`() {
        val checker = checker(
            joinedCommunity = null,
            roomInfo = null,
        )

        assertThat(checker.check(communityUrl())).isEqualTo(
            LinkType.CommunityLink(
                url = communityUrl(),
                name = ROOM,
                joined = false,
            )
        )
    }

    @Test
    fun `uses cached room name and joined flag when matching community already exists`() {
        val checker = checker(
            joinedCommunity = mockJoinedCommunity(),
            roomInfo = roomInfo(name = "Session Lounge"),
        )

        assertThat(checker.check(communityUrl())).isEqualTo(
            LinkType.CommunityLink(
                url = communityUrl(),
                name = "Session Lounge",
                joined = true,
            )
        )
    }

    private fun checker(
        joinedCommunity: GroupInfo.CommunityGroupInfo?,
        roomInfo: OpenGroupApi.RoomInfo?,
    ): LinkChecker {
        val configFactory = mockk<ConfigFactoryProtocol>(relaxed = true)
        val userConfigs = mockk<UserConfigs>()
        val userGroups = mockk<ReadableUserGroupsConfig>()
        val communityDatabase = mockk<CommunityDatabase>()

        every { configFactory.dangerouslyAccessUserConfigs() } returns (userConfigs to {})
        every { userConfigs.userGroups } returns userGroups
        every { userGroups.getCommunityInfo(SERVER, ROOM) } returns joinedCommunity
        every { communityDatabase.getRoomInfo(any()) } returns roomInfo

        return LinkChecker(
            rules = listOf(
                CommunityLinkRule(
                    configFactory = configFactory,
                    communityDatabase = communityDatabase,
                )
            )
        )
    }

    private fun mockJoinedCommunity(): GroupInfo.CommunityGroupInfo {
        return mockk(relaxed = true)
    }

    private fun roomInfo(name: String): OpenGroupApi.RoomInfo {
        return OpenGroupApi.RoomInfo(
            token = ROOM,
            details = OpenGroupApi.RoomInfoDetails(
                token = ROOM,
                name = name,
            )
        )
    }

    private fun communityUrl(publicKey: String = PUBLIC_KEY): String {
        return "$SERVER/$ROOM?public_key=$publicKey"
    }

    private companion object {
        const val SERVER = "https://session.example"
        const val ROOM = "session-room"
        val PUBLIC_KEY = "a".repeat(64)
    }
}
