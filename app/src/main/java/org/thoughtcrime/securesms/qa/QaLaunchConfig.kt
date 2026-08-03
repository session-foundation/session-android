package org.thoughtcrime.securesms.qa

import android.content.Intent
import network.loki.messenger.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.session.libsession.utilities.Environment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log

/**
 * Applies automated-test configuration supplied as launch intent extras.
 *
 * This is the Android counterpart of iOS's
 * `DeveloperSettingsViewModel.processUnitTestEnvVariablesIfNeeded`, which reads the same kind of
 * settings out of the process environment. Android apps can't read the launcher's environment, so
 * the equivalent channel is intent extras on the launch activity — which Appium can set with the
 * `appium:optionalIntentArguments` capability:
 *
 * ```
 * 'appium:optionalIntentArguments': '--es sessionDevnetSeedUrl http://10.0.0.1:1280'
 * ```
 *
 * ## Security
 *
 * The launcher is an `android:exported="true"` activity-alias, so ANY app on the device can start it
 * with extras. Acting on them is therefore gated behind [BuildConfig.ALLOW_QA_LAUNCH_CONFIG], which
 * is false for `release`/`releaseWithDebugMenu` and true only for `debug`/`qa`/`automaticQa`. Because
 * it is a compile-time constant, R8 removes this whole code path from builds that don't opt in.
 *
 * Never gate on anything weaker (a runtime pref, a string comparison on build type): that would turn
 * an exported launcher into a way for a third-party app to repoint a release build's network.
 *
 * ## Timing
 *
 * Values are persisted to preferences rather than held in memory, because the components that read
 * them (e.g. `SnodeDirectory.seedNodePool`) are app-scoped singletons that may resolve before or
 * after the first activity is created. Persisting means the value is guaranteed to be in effect on
 * the NEXT launch; callers that need it applied deterministically should force-restart the app after
 * the first launch (which is what the appium harness does).
 */
object QaLaunchConfig {
    private const val TAG = "QaLaunchConfig"

    /** Seed node to use when the environment is devnet. Must be a valid http(s) URL. */
    private const val EXTRA_DEVNET_SEED_URL = "sessionDevnetSeedUrl"

    /**
     * Which service network to use: `mainnet`, `testnet` or `devnet` — the same vocabulary iOS
     * accepts for its `serviceNetwork` launch variable.
     */
    private const val EXTRA_SERVICE_NETWORK = "sessionServiceNetwork"

    /**
     * Read any supported extras off [intent] and persist them. Safe to call on every launch: absent
     * extras leave the corresponding preference untouched.
     */
    fun apply(intent: Intent?, prefs: TextSecurePreferences) {
        if (!BuildConfig.ALLOW_QA_LAUNCH_CONFIG) {
            return
        }

        if (intent == null) {
            return
        }

        // Reading extras deserialises an attacker-supplied Bundle: because the launcher alias is
        // exported, any app can hand us extras referencing a Parcelable class we don't have, and the
        // unparcel then throws BadParcelableException. Left uncaught that is a remote crash-on-launch
        // for every QA build. Every read below can be the one that triggers the unparcel (which read
        // exactly depends on the API level's lazy-unparcelling behaviour), so the whole block is
        // guarded rather than just the first access. A malformed Bundle is treated as no config.
        try {
            val extras = intent.extras ?: return
            if (extras.isEmpty) {
                return
            }

            // Order matters: point the devnet at the right seed BEFORE switching the environment onto it.
            applyDevnetSeedUrl(intent, prefs)
            applyServiceNetwork(intent, prefs)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Ignoring unreadable launch extras", e)
            return
        }

        // Note there is deliberately no cache invalidation here. A pool cached from the previous
        // network is discarded by SnodeDirectory itself, which compares the pool against the seed
        // configuration it was fetched from. Doing it here would be both redundant and wrong: this
        // runs before the new configuration takes effect, so the current launch would simply refill
        // the pool from the OLD network again.
    }

    /**
     * Switches the service network, mirroring iOS's `serviceNetwork` launch variable.
     *
     * Unlike the debug menu's equivalent (which deliberately wipes all local data and restarts,
     * because switching networks invalidates an existing account) this only writes the preference.
     * That is correct for automation, where every run starts from a fresh install and so has nothing
     * to invalidate — but it means this must not be used to flip a populated install between
     * networks. It is a no-op when the requested network is already active.
     */
    private fun applyServiceNetwork(intent: Intent, prefs: TextSecurePreferences): Boolean {
        if (!intent.hasExtra(EXTRA_SERVICE_NETWORK)) {
            return false
        }

        val raw = intent.getStringExtra(EXTRA_SERVICE_NETWORK).orEmpty().trim()
        val requested = parseEnvironment(raw)
        if (requested == null) {
            Log.e(
                TAG,
                "Ignoring unknown '$EXTRA_SERVICE_NETWORK' extra: '$raw'. Use mainnet | testnet | devnet."
            )
            return false
        }

        if (requested == prefs.getEnvironment()) {
            Log.i(TAG, "Service network already ${requested.label}")
            return false
        }

        Log.i(TAG, "Setting service network to ${requested.label} (takes effect on next launch)")
        prefs.setEnvironment(requested)
        return true
    }

    /** Accepts the iOS-style tokens (`devnet`) as well as the enum's own names (`DEV_NET`). */
    private fun parseEnvironment(raw: String): Environment? {
        val normalised = raw.lowercase().replace("_", "")
        return Environment.entries.firstOrNull {
            it.name.lowercase().replace("_", "") == normalised || it.label.lowercase() == normalised
        }
    }

    private fun applyDevnetSeedUrl(intent: Intent, prefs: TextSecurePreferences): Boolean {
        // Deliberately distinguishes "absent" from "present but empty": passing an empty value is how
        // a test asks to clear a previously-set override and fall back to the built-in seed.
        if (!intent.hasExtra(EXTRA_DEVNET_SEED_URL)) {
            return false
        }

        val raw = intent.getStringExtra(EXTRA_DEVNET_SEED_URL).orEmpty().trim()
        val current = prefs.getDevnetSeedUrl()

        if (raw.isEmpty()) {
            if (current == null) {
                return false
            }
            Log.i(TAG, "Clearing devnet seed URL override")
            prefs.setDevnetSeedUrl(null)
            return true
        }

        if (raw.toHttpUrlOrNull() == null) {
            // Rejected here as well as at the point of use, so a typo shows up in the log at launch
            // instead of surfacing much later as an unexplained network failure.
            Log.e(TAG, "Ignoring malformed '$EXTRA_DEVNET_SEED_URL' extra: '$raw'")
            return false
        }

        if (raw == current) {
            Log.i(TAG, "Devnet seed URL override already set to $raw")
            return false
        }

        Log.i(TAG, "Setting devnet seed URL override to $raw (takes effect on next launch)")
        prefs.setDevnetSeedUrl(raw)
        return true
    }
}
