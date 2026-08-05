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
            // What *is* admin-only is the prune below (§5.1): libsession never hands a read-only
            // config its obsolete hashes, and a member subaccount has no Delete access anyway. Those
            // two facts cancel rather than compound — a member re-stores and simply never prunes.
            //
            // The keys config is absent here and cannot be added: libsession exposes no way to
            // re-serialise keys that have already been loaded (only pendingConfig(), which is a key
            // message that hasn't been pushed yet), and each member holds a different subset of the
            // keys namespace anyway because key supplements are encrypted per session id. A group
            // whose keys have expired is detected and flagged — that's what the expired-group banner
            // is for, since only an admin rekey can fix it — but never recovered.
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
