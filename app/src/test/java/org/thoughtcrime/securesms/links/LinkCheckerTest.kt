package org.thoughtcrime.securesms.links

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import network.loki.messenger.libsession_util.ReadableUserGroupsConfig
import network.loki.messenger.libsession_util.util.GroupInfo
import org.junit.After
import org.junit.Test
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.utilities.CommunityUrlParser
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.UserConfigs
import org.session.libsignal.utilities.Base64
import org.thoughtcrime.securesms.database.CommunityDatabase

class LinkCheckerTest {

    @After
    fun tearDown() {
        unmockkObject(CommunityUrlParser)
    }

    @Test
    fun `returns generic link when no rule matches`() = runTest {
        val checker = LinkChecker(rules = emptyList())

        assertThat(checker.check(" https://getsession.org ")).isEqualTo(
            LinkType.GenericLink("https://getsession.org")
        )
    }

    @Test
    fun `returns generic link for a regular url`() = runTest {
        val checker = checker(
            joinedCommunity = null,
            roomInfo = null,
        )

        assertThat(checker.check("https://getsession.org")).isEqualTo(
            LinkType.GenericLink("https://getsession.org")
        )
    }

    @Test
    fun `detects community links and falls back to room token when no local name exists`() = runTest {
        val checker = checker(
            joinedCommunity = null,
            roomInfo = null,
        )

        assertThat(checker.check(communityUrl())).isEqualTo(
            LinkType.CommunityLink(
                url = communityUrl(),
                name = ROOM,
                joined = false,
                displayType = LinkType.CommunityLink.DisplayType.CONVERSATION,
            )
        )
    }

    @Test
    fun `uses cached room name and joined flag when matching community already exists`() = runTest {
        val checker = checker(
            joinedCommunity = mockJoinedCommunity(),
            roomInfo = roomInfo(name = "Session Lounge"),
        )

        assertThat(checker.check(communityUrl())).isEqualTo(
            LinkType.CommunityLink(
                url = communityUrl(),
                name = "Session Lounge",
                joined = true,
                displayType = LinkType.CommunityLink.DisplayType.CONVERSATION,
            )
        )
    }

    @Test
    fun `detects r path community links with base64 pubkeys`() = runTest {
        val checker = checker(
            joinedCommunity = null,
            roomInfo = null,
        )

        assertThat(checker.check(communityUrl(pathPrefix = "/r/", publicKey = base64PublicKey()))).isEqualTo(
            LinkType.CommunityLink(
                url = communityUrl(pathPrefix = "/r/", publicKey = base64PublicKey()),
                name = ROOM,
                joined = false,
                displayType = LinkType.CommunityLink.DisplayType.CONVERSATION,
            )
        )
    }

    @Test
    fun `detects community links with base32z pubkeys`() = runTest {
        val checker = checker(
            joinedCommunity = null,
            roomInfo = null,
        )

        assertThat(checker.check(communityUrl(publicKey = base32zPublicKey()))).isEqualTo(
            LinkType.CommunityLink(
                url = communityUrl(publicKey = base32zPublicKey()),
                name = ROOM,
                joined = false,
                displayType = LinkType.CommunityLink.DisplayType.CONVERSATION,
            )
        )
    }

    private fun checker(
        joinedCommunity: GroupInfo.CommunityGroupInfo?,
        roomInfo: OpenGroupApi.RoomInfo?,
    ): LinkChecker {
        mockkObject(CommunityUrlParser)
        every { CommunityUrlParser.parse(any()) } answers {
            when (invocation.args[0] as String) {
                communityUrl(),
                communityUrl(pathPrefix = "/r/", publicKey = base64PublicKey()),
                communityUrl(publicKey = base32zPublicKey()) -> CommunityUrlParser.CommunityUrlInfo(
                    baseUrl = SERVER,
                    room = ROOM,
                    pubKeyHex = PUBLIC_KEY,
                )
                else -> throw CommunityUrlParser.Error.InvalidUrl
            }
        }

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

    private fun communityUrl(
        pathPrefix: String = "/",
        publicKey: String = PUBLIC_KEY,
    ): String {
        return "$SERVER$pathPrefix$ROOM?public_key=$publicKey"
    }

    private fun base64PublicKey(): String = Base64.encodeBytesWithoutPadding(publicKeyBytes())

    private fun base32zPublicKey(): String {
        val alphabet = "ybndrfg8ejkmcpqxot1uwisza345h769"
        val bytes = publicKeyBytes()
        val output = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0

        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                output.append(alphabet[(buffer shr bits) and 0x1f])
            }
        }

        if (bits > 0) {
            output.append(alphabet[(buffer shl (5 - bits)) and 0x1f])
        }

        return output.toString()
    }

    private fun publicKeyBytes(): ByteArray = ByteArray(32) { 0xaa.toByte() }

    private companion object {
        const val SERVER = "https://session.example"
        const val ROOM = "session-room"
        val PUBLIC_KEY = "a".repeat(64)
    }
}
