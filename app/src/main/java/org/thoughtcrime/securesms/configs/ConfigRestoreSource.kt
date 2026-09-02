package org.thoughtcrime.securesms.configs

import network.loki.messenger.libsession_util.MutableConfig
import network.loki.messenger.libsession_util.Namespace
import network.loki.messenger.libsession_util.ReadableGroupKeysConfig
import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.ConfigPush
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.getGroup
import org.session.libsession.utilities.withGroupConfigs
import org.session.libsession.utilities.withMutableGroupConfigs
import org.session.libsession.utilities.withMutableUserConfigs
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ConfigRestoreSource"

/** One config's worth of messages to put back on the swarm. */
class PendingRestore(
    val label: String,
    val push: ConfigPush,

    /**
     * Every hash this restore accounts for, which for a multipart config is *all* of its parts and
     * not just the missing one — we re-store the whole config, so a later poll reporting a different
     * part missing is already covered.
     */
    val claimedHashes: Set<String>,

    /**
     * Whether this restore is a group's *keys* config. Recovery needs to know, because a successful keys
     * re-store is what clears the expired-group banner — and it has to be a typed fact rather than a match
     * on [label], which is a human-readable string nobody has promised to keep stable.
     */
    val isGroupKeys: Boolean = false,

    namespace: () -> Int,
) {
    /**
     * Resolved lazily because libsession's [Namespace] is a native class whose initialiser loads the
     * shared library. Keeping it out of construction is what lets the guards that decide whether a
     * config is restorable at all be covered by plain JVM unit tests.
     */
    val namespace: Int by lazy(namespace)
}

/**
 * Whether a config the swarm has apparently lost should be put back.
 *
 * Two conditions, both of which exist to stop recovery becoming a way to *change* state:
 *
 * - The device must still consider one of the missing hashes current. A hash that has dropped out of
 *   [activeHashes] has been superseded locally, so re-storing it would resurrect state we've already
 *   moved on from.
 * - The config must be clean. Recovery re-uploads existing state and never creates new state; a config
 *   with changes of its own is already on its way up via [ConfigUploader].
 *
 *   The clean check carries more weight than that on its own suggests, for a reason that isn't visible
 *   from here: a config dumped while *dirty* is reloaded as a mutable message with no trusted
 *   signature, so it loses the signature it was received with. For a group member — who has no signing
 *   key to make a new one — the bytes would then no longer reproduce the original hash. Recovery only
 *   ever touches clean configs, so the property it depends on holds exactly where it runs.
 *
 * **How reachable the clean check is, since it looks redundant and mostly is.** Dirtying a config moves
 * its current hashes into the *old* set and clears them (libsession `base.cpp`, `set_state`), so a dirty
 * config's [activeHashes] usually no longer contains anything the swarm reported missing — and the first
 * condition rejects it before this one is consulted.
 *
 * It is **not** wholly redundant, though, and the exception is the reason to keep it: [activeHashes] is
 * current hashes *plus the parts of any pending multipart set* that is neither done nor expired, and that
 * second component survives dirtying. So a config that went dirty while a multipart set was still
 * arriving, one of whose part hashes the swarm has lost, reaches this check with a non-empty
 * intersection. Rare, and exactly the case where re-uploading would fight the uploader.
 *
 * That reachability rests on libsession's behaviour rather than ours, and **cannot be asserted here**: it
 * would need `activeHashes()` to run against the real native library, which no JVM unit test in this
 * project can load. The tests below reach this branch through a mocked config, which can present
 * dirty-with-intersecting-hashes freely. So do not delete this check on the grounds that no test drives
 * it from a realistic state, and do not delete the tests on the grounds the check looks unreachable.
 */
internal fun shouldRestore(
    label: String,
    activeHashes: Set<String>,
    needsPush: Boolean,
    missingHashes: Set<String>,
): Boolean {
    if (missingHashes.none { it in activeHashes }) {
        return false
    }

    if (needsPush) {
        Log.d(TAG, "Skipping recovery of $label: it has changes pending")
        return false
    }

    return true
}

/**
 * Turns "the swarm has lost these hashes" into the specific config messages worth re-uploading,
 * applying the guards in [shouldRestore] plus the group-specific ones.
 */
@Singleton
class ConfigRestoreSource @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
) {
    fun userConfigsToRestore(missingHashes: Set<String>): List<PendingRestore> {
        return configFactory.withMutableUserConfigs { configs ->
            UserConfigType.entries.mapNotNull { type ->
                configs.getConfig(type).toRestore(
                    label = "user config $type",
                    missingHashes = missingHashes,
                    namespace = { type.namespace },
                )
            }
        }
    }

    fun groupConfigsToRestore(
        groupId: AccountId,
        missingHashes: Set<String>,
    ): List<PendingRestore> {
        val group = configFactory.getGroup(groupId)

        // Re-storing is impossible for these anyway — the credentials were cleared and the subaccount
        // token revoked — so trying only generates auth failures.
        if (group == null || group.kicked || group.destroyed) {
            Log.d(TAG, "Not recovering configs for a group we're no longer in")
            return emptyList()
        }

        return configFactory.withMutableGroupConfigs(groupId) { configs ->
            // Any member can re-store these, admin or not — and that is the point, because a group
            // whose admins have gone quiet is exactly the group whose configs expire. A member holds
            // info and members read-only, but a read-only config re-emits the signature it received
            // verbatim, and that signature survives the dump round trip, so the bytes it produces are
            // identical to the admin's. Do not gate this on adminKey.
            //
            // What *is* admin-only is the prune below: libsession never hands a read-only
            // config its obsolete hashes, and a member subaccount has no Delete access anyway. Those
            // two facts cancel rather than compound — a member re-stores and simply never prunes.
            //
            // Keys are recoverable too, and NOT via an admin path: libsession retains the raw bytes of
            // every keys message this device has *loaded*, and pushing those bytes back lands on the same
            // hash without being re-signed. A member holding them can repair the group; an admin
            // immediately after its own rekey holds nothing for the message it just created and is the
            // device *least* able to. An admin rekey is the remedy only when no device anywhere still
            // holds the bytes — which is what the banner is for.
            listOfNotNull(
                configs.groupInfo.toRestore(
                    label = "group info for $groupId",
                    missingHashes = missingHashes,
                    namespace = { Namespace.GROUP_INFO() },
                ),
                configs.groupMembers.toRestore(
                    label = "group members for $groupId",
                    missingHashes = missingHashes,
                    namespace = { Namespace.GROUP_MEMBERS() },
                ),
                configs.groupKeys.keysToRestore(
                    label = "group keys for $groupId",
                    missingHashes = missingHashes,
                ),
            )
        }
    }

    /**
     * Whether this device could put back a group's keys, and if so the bytes to send.
     *
     * The one predicate behind both the expired-group flag and the re-store itself, shared rather than
     * duplicated on purpose: if the two ever disagreed in the direction "flag says repairable, recovery
     * declines", the banner would never appear AND nothing would be fixed — silently, and for good.
     *
     * Returns null when no hash the swarm has lost is one we hold bytes for. Holding bytes for messages
     * that are all still present is not a reason to write anything.
     */
    private fun ReadableGroupKeysConfig.retainedKeysCovering(
        missingHashes: Set<String>,
    ): Map<String, ByteArray>? {
        val retained = activeKeyMessages()
        return retained.takeIf { missingHashes.any { hash -> hash in it.keys } }
    }

    /**
     * Whether the expired-group banner should be withheld for [groupId] because this device can repair it.
     *
     * Deliberately **not** gated on the things that gate the *action* — foreground, backoff, being level
     * with the swarm. Those decide whether to write now; this decides whether the group is beyond reach at
     * all, and a group whose repair is merely deferred until the app is foregrounded is not expired. Gating
     * this on them would raise a banner that a later poll takes away, which is the flicker v119(a) exists
     * to avoid.
     */
    fun canRepairGroupKeys(groupId: AccountId, missingHashes: Set<String>): Boolean {
        val group = configFactory.getGroup(groupId)
        if (group == null || group.kicked || group.destroyed) {
            // No credentials and a revoked subaccount token: the bytes are irrelevant, we cannot store.
            return false
        }

        return configFactory.withGroupConfigs(groupId) { configs ->
            configs.groupKeys.retainedKeysCovering(missingHashes) != null
        }
    }

    /**
     * The keys equivalent of [toRestore], and deliberately not the same function.
     *
     * **Every retained message goes back, not just the missing ones.** A generation is a rekey plus every
     * supplemental issued against it, and a member who receives only part of a generation cannot derive the
     * key — so a partial re-store is worse than none. The retained set is not keyed by generation and
     * carries no generation field, so grouping is not expressible here; re-storing all of it is a strict
     * superset of "the affected generation" and therefore satisfies the requirement a fortiori. It is
     * bounded (retention follows the key expiry) and idempotent, since each message is byte-identical to
     * what the swarm already had and lands on the same hash.
     *
     * None of the [shouldRestore] guards apply. There is no `needsPush` for keys — a pending rekey is a
     * *new* message, not a re-store of an old one, and it goes out through the uploader. There is no
     * obsolete-hash list either, so nothing is pruned and no delete is issued.
     *
     * Returns null when this device holds no bytes for any missing hash, which is the case the
     * expired-group banner exists for.
     */
    private fun ReadableGroupKeysConfig.keysToRestore(
        label: String,
        missingHashes: Set<String>,
    ): PendingRestore? {
        val retained = retainedKeysCovering(missingHashes) ?: return null

        Log.d(TAG, "Restoring all ${retained.size} retained keys message(s) for $label")

        return PendingRestore(
            label = label,
            push = ConfigPush(
                messages = retained.values.map { Bytes(it) },
                seqNo = 0L,
                obsoleteHashes = emptyList(),
            ),
            claimedHashes = retained.keys,
            isGroupKeys = true,
            namespace = { Namespace.GROUP_KEYS() },
        )
    }

    /**
     * [namespace] stays a lambda all the way into [PendingRestore] — see the note there. Note that a
     * bound reference such as `Namespace::GROUP_INFO` would defeat the point: it resolves its receiver
     * eagerly, loading the class at the point the reference is created.
     */
    private fun MutableConfig.toRestore(
        label: String,
        missingHashes: Set<String>,
        namespace: () -> Int,
    ): PendingRestore? {
        // Read the hashes before pushing: push() marks pending multipart sets as done, which can drop
        // them back out of activeHashes.
        val active = activeHashes().toSet()

        if (!shouldRestore(label, active, needsPush(), missingHashes)) {
            return null
        }

        return PendingRestore(
            label = label,
            push = push(),
            claimedHashes = active,
            namespace = namespace,
        )
    }
}
