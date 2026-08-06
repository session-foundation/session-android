package org.thoughtcrime.securesms.configs

import network.loki.messenger.libsession_util.MutableConfig
import network.loki.messenger.libsession_util.Namespace
import network.loki.messenger.libsession_util.util.ConfigPush
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.getGroup
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
            // The keys config is absent here, and the reason is the **Android wrapper**, not libsession:
            // libsession retains the raw bytes of every active keys message and exposes them
            // (Keys::active_key_messages), but no JNI binding for that exists yet. The only keys bytes
            // reachable from Kotlin are pendingConfig(), which is a message this device authored and has
            // not pushed — the wrong thing entirely, because retention is a property of having *loaded* a
            // message, not of having authored one. So a group whose keys have expired is detected and
            // flagged, and not recovered, until that binding lands.
            //
            // When it does, note what it is *not*: an admin path. Any device that loaded the message holds
            // the bytes and can push them back unchanged — a member included, since the bytes carry the
            // admin's signature already. Immediately after its own rekey an admin holds no bytes for the
            // message it just created and is the device *least* able to repair it. An admin rekey remains
            // the only remedy when no device anywhere still holds the bytes, which is what the banner is
            // for; it is not the remedy when some device does.
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
            )
        }
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
