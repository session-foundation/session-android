package org.thoughtcrime.securesms.dependencies

import androidx.test.ext.junit.runners.AndroidJUnit4
import network.loki.messenger.libsession_util.Curve25519
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.GroupInfoConfig
import network.loki.messenger.libsession_util.GroupKeysConfig
import network.loki.messenger.libsession_util.GroupMembersConfig
import network.loki.messenger.libsession_util.util.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.session.libsession.utilities.ConfigMessage

/**
 * A supplemental keys message, built by libsession, loaded by devices it gives no key to.
 *
 * `loadKey` returns false for these, so a merge that counts only `true` as taken in reads every
 * supplemental as a failed merge. That withdraws the group from recovery for the session, and it happens
 * whenever a member is added with history. The shape is the library's, so it is asserted against the
 * library: a mocked `loadKey` would only repeat what the test's author believed it returns.
 *
 * This has to be an instrumented test: no JVM unit test in this project can load `libsession_util.so`.
 */
@RunWith(AndroidJUnit4::class)
class SupplementalKeysLoadTest {

    private val group = ED25519.generate(null)
    private val admin = ED25519.generate(null)
    private val addedWithHistory = ED25519.generate(null)
    private val otherMember = ED25519.generate(null)

    private val now = System.currentTimeMillis()

    /** Another device of the admin: an admin never decrypts a supplemental, so it gets no key from one. */
    @Test
    fun anAdminTakesInASupplemental() {
        assertTakenIn { keysFor(admin, groupAdminKey = group.secretKey.data) }
    }

    @Test
    fun aMemberTheSupplementalIsNotAddressedToTakesItIn() {
        assertTakenIn { keysFor(otherMember, groupAdminKey = null) }
    }

    private fun assertTakenIn(device: () -> GroupKeysConfig) {
        val (full, supplemental) = fullAndSupplementalMessages()

        // The library's own answer for each message, so that the count below cannot pass because the
        // supplemental returned true after all.
        val probe = device()
        assertTrue(probe.loadKey(full.data, full.hash, full.timestamp))
        assertFalse(probe.loadKey(supplemental.data, supplemental.hash, supplemental.timestamp))

        // The production count, on a fresh device, over both messages as a poll delivers them.
        val keys = device()
        val load = keys.loadKeyMessages(listOf(full, supplemental))

        assertEquals(2, load.takenIn)
        assertTrue(load.gaveUsAKey)
        assertTrue(supplemental.hash in keys.activeHashes())
    }

    /** A full keys message for all three members, then a supplemental giving its key to one of them. */
    private fun fullAndSupplementalMessages(): Pair<ConfigMessage, ConfigMessage> {
        val info = GroupInfoConfig(group.pubKey.data, group.secretKey.data, null)
        val members = GroupMembersConfig(group.pubKey.data, group.secretKey.data, null)
        for (user in listOf(admin, addedWithHistory, otherMember)) {
            members.set(members.getOrConstruct(sessionId(user)))
        }
        val keys = GroupKeysConfig(
            userSecretKey = admin.secretKey.data,
            groupPublicKey = group.pubKey.data,
            groupAdminKey = group.secretKey.data,
            initialDump = null,
            info = info,
            members = members,
        )

        val full = keys.rekey(info.pointer, members.pointer)
        // A rekey's key stays pending until the admin loads the message back, as it does once the push
        // lands; a supplemental cannot be built before then.
        check(keys.loadKey(full, "full-1", now))
        val supplemental = keys.supplementFor(listOf(sessionId(addedWithHistory)))

        return ConfigMessage("full-1", full, now) to ConfigMessage("supplemental-1", supplemental, now + 1)
    }

    private fun keysFor(user: KeyPair, groupAdminKey: ByteArray?): GroupKeysConfig {
        val info = GroupInfoConfig(group.pubKey.data, groupAdminKey, null)
        val members = GroupMembersConfig(group.pubKey.data, groupAdminKey, null)
        return GroupKeysConfig(
            userSecretKey = user.secretKey.data,
            groupPublicKey = group.pubKey.data,
            groupAdminKey = groupAdminKey,
            initialDump = null,
            info = info,
            members = members,
        )
    }

    private fun sessionId(user: KeyPair): String =
        "05" + Curve25519.pubKeyFromED25519(user.pubKey.data).joinToString("") { "%02x".format(it) }
}
