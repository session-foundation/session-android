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
     * V23 — keys ARE restorable when this device holds their bytes, and a **member** can do it: the retained
     * message carries the admin's signature already, so pushing it back lands on the same hash without being
     * re-signed. This fixture is a non-admin deliberately.
     */
    @Test
    fun `V23 - a missing keys hash whose bytes are held is restorable by a member`() {
        givenGroup(adminKey = null)
        givenGroupConfigs(retainedKeys = mapOf("keys-1" to "keys-one".toByteArray()))

        val restores = source.groupConfigsToRestore(groupId, setOf("keys-1"))

        val keys = restores.single { it.isGroupKeys }
        assertEquals(setOf("keys-1"), keys.claimedHashes)
        assertEquals(listOf("keys-one"), keys.push.messages.map { String(it.data) })
        // No obsolete-hash list for keys, so nothing is ever pruned on this path.
        assertEquals(emptyList(), keys.push.obsoleteHashes)
    }

    /**
     * V23b — a supplemental is retained and re-stored **at all**.
     *
     * A generation is a rekey plus every supplemental issued against it, and a member who receives only part
     * of one cannot derive the key. Retention is keyed by message hash, not by generation, so all of it goes
     * back whenever any of it is missing — which is a strict superset of "the affected generation" and the
     * only form expressible on this API, since the retained map carries no generation field.
     *
     * What this pins is that supplementals are not silently dropped: it is the test that fails if someone
     * "tidies" the cache to be keyed by generation, or re-stores only the hash the swarm reported.
     */
    @Test
    fun `V23b - every retained keys message is re-stored, supplementals included`() {
        givenGroup(adminKey = null)
        givenGroupConfigs(
            retainedKeys = mapOf(
                "rekey-1" to "the-rekey".toByteArray(),
                "supplement-1" to "for-alice".toByteArray(),
                "supplement-2" to "for-bob".toByteArray(),
            )
        )

        // Only ONE hash is reported missing; all three must still go back.
        val keys = source.groupConfigsToRestore(groupId, setOf("supplement-1")).single { it.isGroupKeys }

        assertEquals(setOf("rekey-1", "supplement-1", "supplement-2"), keys.claimedHashes)
        assertEquals(3, keys.push.messages.size)
    }

    @Test
    fun `canRepairGroupKeys is true only when a MISSING hash is one we hold`() {
        givenGroup(adminKey = null)
        givenGroupConfigs(retainedKeys = mapOf("keys-1" to "keys-one".toByteArray()))

        assertTrue(source.canRepairGroupKeys(groupId, setOf("keys-1")))

        // Holding bytes for messages the swarm still has is not a reason to write, and must not withhold
        // the banner for a group whose *other* keys are genuinely gone.
        assertFalse(source.canRepairGroupKeys(groupId, setOf("keys-99")))
    }

    /**
     * A kicked group cannot be repaired however many bytes we hold — the credentials are gone and the
     * subaccount token is revoked, so the store would only generate auth failures. The flag must stand.
     */
    @Test
    fun `canRepairGroupKeys is false for a kicked group even holding the bytes`() {
        givenGroup(adminKey = null, kicked = true)
        givenGroupConfigs(retainedKeys = mapOf("keys-1" to "keys-one".toByteArray()))

        assertFalse(source.canRepairGroupKeys(groupId, setOf("keys-1")))
    }

    /**
     * The keys config is not in the restorable set at all, and a missing keys hash goes straight to the
     * expired-group flag instead.
     *
     * ⚠️ This is a **platform** limitation with a known expiry date, not a property of the format: libsession
     * retains the bytes of active keys messages and exposes them, and once the Android wrapper binds that
     * accessor a member will be able to repair a group's keys by pushing the retained bytes back. At that
     * point this test inverts rather than being deleted — keys become restorable when their bytes are held,
     * and the flag is only for a device that holds none.
     *
     * Deliberately unlabelled: V16 is the *detection* rule (all keys hashes gone ⇒ group expired) and lives
     * in ConfigExpiryDetectionTest. This is the same input on the *restore* path, which is a separate
     * question the vector table doesn't have a row for. It carried "V16" until a sweep found that label
     * already on the detection test.
     */
    @Test
    fun `a missing group keys hash produces no re-store while the wrapper exposes no key bytes`() {
        // The keys hash is not among any restorable config's active hashes, so nothing matches.
        assertEquals(emptyList(), source.groupConfigsToRestore(groupId, setOf("keys-hash")))

        // Reachability control, same reasoning as V11: a hash that IS restorable must produce restores
        // through this fixture, or the assertion above proves only that nothing ran.
        assertEquals(2, source.groupConfigsToRestore(groupId, setOf(h2)).size)
    }

    /**
     * Note what this does *not* establish: the mock presents dirty-with-intersecting-hashes directly, and
     * a real config reaches that state only through the pending-multipart component of `activeHashes()`,
     * since dirtying clears the current hashes. Driving it realistically needs the native library. See
     * [shouldRestore]'s doc for why the check stays regardless.
     */
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
        retainedKeys: Map<String, ByteArray> = emptyMap(),
    ) {
        val configs = mockk<MutableGroupConfigs>()
        every { configs.groupInfo } returns
                mockk<MutableGroupInfoConfig>().withState(activeHashes, obsoleteHashes = obsoleteHashes)
        every { configs.groupMembers } returns
                mockk<MutableGroupMembersConfig>().withState(activeHashes, obsoleteHashes = obsoleteHashes)
        every { configs.groupKeys } returns mockk<MutableGroupKeysConfig>(relaxed = true).also {
            every { it.activeKeyMessages() } returns retainedKeys
        }

        every { configFactory.dangerouslyAccessMutableGroupConfigs(groupId) } returns (configs to {})
        // canRepairGroupKeys reads through the *read-only* accessor, so it needs its own stub — the
        // mutable one is not a superset here.
        every { configFactory.dangerouslyAccessGroupConfigs(groupId) } returns (configs to {})
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
