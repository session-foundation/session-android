package org.thoughtcrime.securesms.configs

import io.mockk.every
import io.mockk.mockk
import network.loki.messenger.libsession_util.MutableConfig
import network.loki.messenger.libsession_util.MutableGroupInfoConfig
import network.loki.messenger.libsession_util.MutableGroupKeysConfig
import network.loki.messenger.libsession_util.MutableGroupMembersConfig
import network.loki.messenger.libsession_util.ReadableUserGroupsConfig
import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.ConfigPush
import network.loki.messenger.libsession_util.util.GroupInfo
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.MutableGroupConfigs
import org.session.libsession.utilities.UserConfigs
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.IdPrefix
import org.thoughtcrime.securesms.util.MockLoggingRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a given config is eligible to be put back at all (V11 and V12), plus the [shouldRestore]
 * rules those cases rest on.
 *
 * Everything here goes through the *group* path, and [shouldRestore] is also exercised directly. The
 * user path can't be driven from a JVM unit test: reaching it touches `UserConfigType`, whose class
 * initialiser resolves `Namespace` and loads the libsession native library, which no unit test in
 * this project has. The rules being checked are the same ones the user path calls.
 */
class ConfigRestoreSourceTest {
    @get:Rule
    val loggingRule = MockLoggingRule()

    private val h1 = "hash-one"
    private val h2 = "hash-two"
    private val groupId = AccountId(IdPrefix.GROUP, ByteArray(32) { 2 })
    private val adminKey = Bytes(ByteArray(64) { 3 })

    private lateinit var configFactory: ConfigFactoryProtocol
    private lateinit var source: ConfigRestoreSource

    @Before
    fun setUp() {
        configFactory = mockk()
        source = ConfigRestoreSource(configFactory)

        givenGroup(adminKey = adminKey)
        givenGroupConfigs()
    }

    @Test
    fun `V11 - a hash the device no longer considers current must not be put back`() {
        // h2 has dropped out of activeHashes: it's been superseded locally, so re-storing it would
        // resurrect state we've already moved on from.
        givenGroupConfigs(activeHashes = listOf(h1))

        assertEquals(emptyList(), source.groupConfigsToRestore(groupId, setOf(h2)))

        // Reachability control. Without it this is an absence assertion satisfied by total inaction:
        // break the shared stub so every config reports holding nothing, and the line above passes
        // while exercising none of the rule it names. h1 IS still current, so it must produce restores
        // through the same fixture.
        assertEquals(2, source.groupConfigsToRestore(groupId, setOf(h1)).size)
    }

    @Test
    fun `V12 - a kicked group must not be put back`() {
        givenGroup(adminKey = adminKey, kicked = true)

        assertEquals(emptyList(), source.groupConfigsToRestore(groupId, setOf(h2)))
    }

    @Test
    fun `V12b - a destroyed group must not be put back`() {
        givenGroup(adminKey = adminKey, destroyed = true)

        assertEquals(emptyList(), source.groupConfigsToRestore(groupId, setOf(h2)))
    }

    @Test
    fun `a group that has left config entirely must not be put back`() {
        givenGroup(adminKey = adminKey, present = false)

        assertEquals(emptyList(), source.groupConfigsToRestore(groupId, setOf(h2)))
    }

    /**
     * V20 — a non-admin member re-storing is a supported path, not a workaround, and gating it on
     * admin status would remove recovery from exactly the groups that need it: a group whose admins
     * have gone quiet is the group whose configs expire. A read-only config re-emits the signature it
     * received verbatim and that signature survives the dump round trip, so a member's bytes are
     * identical to an admin's.
     */
    @Test
    fun `V20 - a non-admin member re-stores group info`() {
        givenGroup(adminKey = null)

        val restores = source.groupConfigsToRestore(groupId, setOf(h2))

        assertEquals(
            listOf("group info for $groupId", "group members for $groupId"),
            restores.map { it.label },
        )
    }

    /**
     * V21 — and a member is never handed obsolete hashes to prune, because libsession skips the
     * hand-back for read-only configs (while still clearing the set). An empty list here is the
     * expected result, not a failure.
     */
    @Test
    fun `V21 - a member re-store carries no obsolete hashes`() {
        givenGroup(adminKey = null)
        givenGroupConfigs(obsoleteHashes = emptyList())

        val restores = source.groupConfigsToRestore(groupId, setOf(h2))

        assertEquals(2, restores.size)
        assertEquals(emptyList(), restores.flatMap { it.push.obsoleteHashes })
    }

    /**
     * V16 — the keys config is not in the restorable set at all. It has no re-serialise API, and a
     * missing keys hash goes straight to the expired-group flag instead.
     */
    @Test
    fun `V16 - a missing group keys hash produces no re-store`() {
        // The keys hash is not among any restorable config's active hashes, so nothing matches.
        assertEquals(emptyList(), source.groupConfigsToRestore(groupId, setOf("keys-hash")))

        // Reachability control, same reasoning as V11: a hash that IS restorable must produce restores
        // through this fixture, or the assertion above proves only that nothing ran.
        assertEquals(2, source.groupConfigsToRestore(groupId, setOf(h2)).size)
    }

    @Test
    fun `a config with changes of its own is left to the uploader`() {
        assertFalse(
            shouldRestore("test", setOf(h1, h2), needsPush = true, missingHashes = setOf(h2))
        )
    }

    @Test
    fun `a clean config still holding the missing hash is restorable`() {
        assertTrue(
            shouldRestore("test", setOf(h1, h2), needsPush = false, missingHashes = setOf(h2))
        )
    }

    @Test
    fun `a config no longer holding the missing hash is not restorable`() {
        assertFalse(
            shouldRestore("test", setOf(h1), needsPush = false, missingHashes = setOf(h2))
        )
    }

    @Test
    fun `one missing part is enough to restore a multipart config`() {
        assertTrue(
            shouldRestore(
                "test",
                setOf("P1", "P2", "P3"),
                needsPush = false,
                missingHashes = setOf("P2"),
            )
        )
    }

    private fun <T : MutableConfig> T.withState(
        activeHashes: List<String>,
        needsPush: Boolean = false,
        obsoleteHashes: List<String> = emptyList(),
    ): T = apply {
        every { activeHashes() } returns activeHashes
        every { needsPush() } returns needsPush
        every { push() } returns ConfigPush(
            messages = listOf(Bytes("config-data".toByteArray())),
            seqNo = 7L,
            obsoleteHashes = obsoleteHashes,
        )
    }

    private fun givenGroupConfigs(
        activeHashes: List<String> = listOf(h1, h2),
        obsoleteHashes: List<String> = emptyList(),
    ) {
        val configs = mockk<MutableGroupConfigs>()
        every { configs.groupInfo } returns
                mockk<MutableGroupInfoConfig>().withState(activeHashes, obsoleteHashes = obsoleteHashes)
        every { configs.groupMembers } returns
                mockk<MutableGroupMembersConfig>().withState(activeHashes, obsoleteHashes = obsoleteHashes)
        every { configs.groupKeys } returns mockk<MutableGroupKeysConfig>(relaxed = true)

        every { configFactory.dangerouslyAccessMutableGroupConfigs(groupId) } returns (configs to {})
    }

    private fun givenGroup(
        adminKey: Bytes?,
        kicked: Boolean = false,
        destroyed: Boolean = false,
        present: Boolean = true,
    ) {
        val userGroups = mockk<ReadableUserGroupsConfig>()
        every { userGroups.getClosedGroup(groupId.hexString) } returns if (!present) {
            null
        } else {
            GroupInfo.ClosedGroupInfo(
                groupAccountId = groupId.hexString,
                adminKey = adminKey,
                authData = if (adminKey == null) Bytes(ByteArray(100) { 4 }) else null,
                priority = 0L,
                invited = false,
                name = "A group",
                kicked = kicked,
                destroyed = destroyed,
                joinedAtSecs = 0L,
            )
        }

        val configs = mockk<UserConfigs>()
        every { configs.userGroups } returns userGroups
        every { configFactory.dangerouslyAccessUserConfigs() } returns (configs to {})
    }
}
