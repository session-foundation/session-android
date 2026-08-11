package org.thoughtcrime.securesms.qa

import android.content.Intent
import android.os.Bundle
import network.loki.messenger.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.utilities.Environment
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel
import org.session.libsignal.utilities.Log
import java.time.Duration
import java.time.Instant

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
 * No restart is needed. Values go to preferences, and everything that resolves the network re-reads
 * them on every access — `SnodeDirectory.seedNodePool` is a getter, not a field captured at
 * construction — so the requested configuration is live from here on.
 *
 * What does not follow automatically is state already derived from the PREVIOUS configuration — a
 * cached snode pool, and the onion paths and swarms built out of it. `SnodeDirectory` owns that
 * problem: it records the seed configuration each pool was fetched under and drops the lot when that
 * no longer matches, so no restart is needed to converge on the requested network.
 *
 * It does have to be told to look, though. That check normally rides along with pool population, and
 * a launch that already has a usable pool and cached paths never populates anything — so it would
 * never run on exactly the launches where it matters. Hence the explicit kick at the end of [apply].
 */
object QaLaunchConfig {
    private const val TAG = "QaLaunchConfig"

    /** iOS's explicit-clear sentinel, accepted on every Pro mock key so both platforms spell it alike. */
    private const val USE_ACTUAL = "useactual"

    /** Seed node to use when the environment is devnet. Must be a valid http(s) URL. */
    private const val EXTRA_DEVNET_SEED_URL = "sessionDevnetSeedUrl"

    /**
     * Which service network to use: `mainnet`, `testnet` or `devnet` — the same vocabulary iOS
     * accepts for its `serviceNetwork` launch variable.
     */
    private const val EXTRA_SERVICE_NETWORK = "sessionServiceNetwork"

    /**
     * Session Pro backend to use instead of the one compiled into libsession, so a QA backend can be
     * targeted without rebuilding. iOS's equivalents are `customProBackendUrl`/`customProBackendPubkey`.
     *
     * Both are required together, and [EXTRA_PRO_BACKEND_PUBKEY] must be the backend's **Ed25519**
     * signing key (`signing_pubkey` from its `GET /status`), not the x25519 form — the x25519 key is
     * derived from it (see ProBackendConfig). A URL paired with the production key verifies every
     * QA-signed proof as invalid and silently strips Pro content, which reads as an app bug rather
     * than a config mistake, so a half-supplied pair is rejected rather than half-applied.
     */
    private const val EXTRA_PRO_BACKEND_URL = "sessionProBackendUrl"
    private const val EXTRA_PRO_BACKEND_PUBKEY = "sessionProBackendPubkey"

    /**
     * Current user's Pro state. Named after the iOS concept rather than the Android preference,
     * because this is a cross-platform contract the Appium suite is written against — iOS's key is
     * `mockCurrentUserSessionProBackendStatus`.
     *
     * `useActual` | `never` | `active` | `expired`. `useActual` is the same explicit-clear sentinel
     * iOS uses on every mockable Pro feature; an ABSENT extra leaves the preferences untouched.
     *
     * Maps to TWO preferences, because Android splits the concerns iOS keeps in one key:
     * `forceCurrentUserAsPro` is the "use mocked state at all" gate, and `DEBUG_SUBSCRIPTION_STATUS`
     * picks which state. Collapsing them here is what keeps one `bothPlatformsIt` setup meaning the
     * same thing on both platforms.
     */
    private const val EXTRA_PRO_BACKEND_STATUS = "sessionProBackendStatus"

    /**
     * When the mocked Pro access expires, overriding the fixed offset the fixture selected by
     * [EXTRA_PRO_BACKEND_STATUS] carries. iOS's `mockCurrentUserAccessExpiryTimestamp`, which is an
     * independent key there too.
     *
     * Two accepted forms, and neither can contain a space or start with `-` because `appium-adb`
     * reads a space-preceded `-`-prefixed token as a new flag:
     *
     * - **Absolute:** epoch **SECONDS**, e.g. `1786407165`. Always positive, so this is how a PAST
     *   instant is expressed — there is deliberately no `-30d` form.
     * - **Relative:** `+<n><unit>` with unit `s`|`m`|`h`|`d`, e.g. `+30d`. Future only, and unit-free
     *   by construction, so prefer it wherever a test only needs an offset.
     *
     * `useActual` clears the override and restores the fixture's own offset.
     *
     * **Seconds, not milliseconds, and this is a cross-platform contract rather than a preference:**
     * iOS's mock is a `TimeInterval` feeding `accessExpiryTimestampSeconds`, and the harness builds the
     * value with `Math.floor(Date.now() / 1000)`. One key name and one value shape per platform is the
     * standing rule for these keys — a per-platform dialect is how a shared spec silently means two
     * things. Note the app's own field name records the unit; prefer it over any doc, including this one.
     *
     * A resolved instant more than [MAX_EXPIRY_SKEW_YEARS] years from now is REJECTED rather than
     * applied, which is what makes a unit slip loud: milliseconds read as seconds lands around the year
     * 58,000, which no test means. The check is deliberately **direction-agnostic** — it bounds the
     * resolved instant rather than inspecting the input's magnitude, so it catches the slip either way
     * and needs no second unit-specific test beside it.
     */
    private const val EXTRA_PRO_ACCESS_EXPIRY = "sessionProAccessExpiry"

    /** Bound on [EXTRA_PRO_ACCESS_EXPIRY], in years either side of now. See its docs. */
    private const val MAX_EXPIRY_SKEW_YEARS = 10L

    /**
     * Load state of the Pro settings screen: `useActual` | `loading` | `error` | `success`.
     * iOS's `mockCurrentUserSessionProLoadingState`. `success` maps to Android's `NORMAL`.
     */
    private const val EXTRA_PRO_LOADING_STATE = "sessionProLoadingState"

    /**
     * Read any supported extras off [intent] and persist them. Safe to call on every launch: absent
     * extras leave the corresponding preference untouched.
     */
    fun apply(intent: Intent?, prefs: TextSecurePreferences, snodeDirectory: SnodeDirectory) {
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

            warnOnUnrecognisedExtras(extras)

            // Order matters: point the devnet at the right seed BEFORE switching the environment onto it.
            applyDevnetSeedUrl(intent, prefs)
            applyServiceNetwork(intent, prefs)
            applyProBackend(intent, prefs)
            applyProBackendStatus(intent, prefs)
            applyProAccessExpiry(intent, prefs)
            applyProLoadingState(intent, prefs)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Ignoring unreadable launch extras", e)
            return
        }

        // Invalidation is delegated rather than done here: SnodeDirectory keys the cached pool on the
        // seed configuration it was fetched under and knows everything derived from it, whereas this
        // class would only ever clear the caches it happened to know about.
        //
        // It does have to be kicked, though. The check normally rides along with pool population, and
        // a launch that already has a usable pool and cached paths never populates anything — so
        // without this the switch would apply to preferences and nothing else. Unconditional: the
        // marker comparison inside is what decides whether there is anything to drop.
        snodeDirectory.discardPoolIfSeedChangedAsync()
    }

    /** Every extra this class acts on. Used only to report the ones it doesn't. */
    private val SUPPORTED_EXTRAS = setOf(
        EXTRA_DEVNET_SEED_URL,
        EXTRA_SERVICE_NETWORK,
        EXTRA_PRO_BACKEND_URL,
        EXTRA_PRO_BACKEND_PUBKEY,
        EXTRA_PRO_BACKEND_STATUS,
        EXTRA_PRO_ACCESS_EXPIRY,
        EXTRA_PRO_LOADING_STATE,
    )

    /**
     * Logs any `session`-prefixed extra this class does not act on.
     *
     * Exists because the rest of the class CANNOT report an unsupported key by construction: each
     * `applyX` asks `hasExtra` for a name it already knows, so a typo'd or not-yet-implemented key is
     * silently a no-op. That makes a setup mistake surface later as a wrong assertion in a spec —
     * the failure arrives far from its cause and looks like a product bug. A key that does nothing is
     * worse than one that errors.
     *
     * Deliberately scoped to the `session` prefix: the launcher also receives Android's own extras
     * (and anything another app cares to send, since the alias is exported), and warning about those
     * would be noise that trains readers to ignore this log.
     */
    private fun warnOnUnrecognisedExtras(extras: Bundle) {
        val unrecognised = extras.keySet()
            .filter { it.startsWith("session") && it !in SUPPORTED_EXTRAS }

        if (unrecognised.isEmpty()) {
            return
        }

        Log.e(
            TAG,
            "Ignoring ${unrecognised.size} unrecognised launch extra(s): " +
                "${unrecognised.sorted()}. Supported: ${SUPPORTED_EXTRAS.sorted()}. " +
                "These had NO effect — check for a typo, or for a key this build does not implement."
        )
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

        Log.i(TAG, "Setting service network to ${requested.label}")
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

    /**
     * Points the app at a different Session Pro backend.
     *
     * Only applied when BOTH extras are present and valid — see [EXTRA_PRO_BACKEND_URL] for why a
     * mismatched pair is worse than no override at all. Passing an empty URL clears the override and
     * falls back to the backend compiled into libsession.
     */
    private fun applyProBackend(intent: Intent, prefs: TextSecurePreferences): Boolean {
        if (!intent.hasExtra(EXTRA_PRO_BACKEND_URL) && !intent.hasExtra(EXTRA_PRO_BACKEND_PUBKEY)) {
            return false
        }

        val rawUrl = intent.getStringExtra(EXTRA_PRO_BACKEND_URL).orEmpty().trim()
        val rawPubkey = intent.getStringExtra(EXTRA_PRO_BACKEND_PUBKEY).orEmpty().trim()

        // Deliberately distinguishes "absent" from "present but empty": an empty URL is how a test
        // asks to clear a previous override.
        if (rawUrl.isEmpty() && rawPubkey.isEmpty()) {
            if (prefs.getProBackendUrl() == null && prefs.getProBackendPubkey() == null) {
                return false
            }
            Log.i(TAG, "Clearing Pro backend override")
            prefs.setProBackendUrl(null)
            prefs.setProBackendPubkey(null)
            return true
        }

        if (rawUrl.toHttpUrlOrNull() == null) {
            Log.e(TAG, "Ignoring Pro backend override: malformed '$EXTRA_PRO_BACKEND_URL' ('$rawUrl')")
            return false
        }

        if (!isEd25519PubKeyHex(rawPubkey)) {
            Log.e(
                TAG,
                "Ignoring Pro backend override: '$EXTRA_PRO_BACKEND_PUBKEY' must be 64 hex characters " +
                    "(the backend's Ed25519 signing_pubkey), got '${rawPubkey.length}' characters"
            )
            return false
        }

        if (rawUrl == prefs.getProBackendUrl() && rawPubkey == prefs.getProBackendPubkey()) {
            Log.i(TAG, "Pro backend override already set to $rawUrl")
            return false
        }

        Log.i(TAG, "Setting Pro backend override to $rawUrl (takes effect on next launch)")
        prefs.setProBackendUrl(rawUrl)
        prefs.setProBackendPubkey(rawPubkey)
        return true
    }

    private fun isEd25519PubKeyHex(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

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

        Log.i(TAG, "Setting devnet seed URL override to $raw")
        prefs.setDevnetSeedUrl(raw)
        return true
    }

    /**
     * Sets the mocked Pro state for the current user.
     *
     * Values are mapped EXPLICITLY rather than derived from the enum names, deliberately: this is an
     * external contract the Appium suite is written against, so it stays readable and stable
     * independently of how [DebugMenuViewModel.DebugSubscriptionStatus] is renamed or reordered. The
     * same reasoning iOS documents for its own key.
     *
     * `expired` is reachable because the debug enum already models it — no new product state was
     * needed. The offsets these fixtures carry are FIXED, so this key expresses *which state* and not
     * *when*; pass [EXTRA_PRO_ACCESS_EXPIRY] alongside it to choose the instant.
     *
     * ## Why `active` selects an EXPIRING fixture rather than an auto-renewing one
     *
     * It looks wrong and is deliberate: iOS's `autoRenewing` is a plain field defaulting to **false**
     * with **no mock key of its own**, so `active` on iOS means "active, not auto-renewing, expiring
     * at the access expiry you gave me" — which is [ProStatus.Active.Expiring] here, not
     * `AutoRenewing`. Mapping to `AUTO_GOOGLE` made the same token mean different things per platform
     * and rendered `proAutoRenewTime` where the shared spec asserts `proExpiringTime`.
     *
     * The deeper mismatch worth knowing before adding another token: **iOS mocks are orthogonal
     * fields, Android's are bundled fixtures.** `active` constrains exactly one field on iOS, while
     * here it selects a whole tuple (status + offset + plan length + provider). That is why
     * [EXTRA_PRO_ACCESS_EXPIRY] exists — it peels the one dimension tests actually vary back out of
     * the bundle. Prefer widening that seam over adding fixtures.
     */
    private fun applyProBackendStatus(intent: Intent, prefs: TextSecurePreferences): Boolean {
        if (!intent.hasExtra(EXTRA_PRO_BACKEND_STATUS)) {
            return false
        }

        val raw = intent.getStringExtra(EXTRA_PRO_BACKEND_STATUS).orEmpty().trim()
        // null = don't mock at all (fall through to the real backend-derived state).
        val mocked: DebugMenuViewModel.DebugSubscriptionStatus? = when (raw.lowercase()) {
            USE_ACTUAL, "never" -> null
            // Expiring, NOT auto-renewing — see the KDoc on EXTRA_PRO_BACKEND_STATUS for why.
            "active" -> DebugMenuViewModel.DebugSubscriptionStatus.EXPIRING_GOOGLE_LATER
            "expired" -> DebugMenuViewModel.DebugSubscriptionStatus.EXPIRED
            else -> {
                Log.e(
                    TAG,
                    "Ignoring unknown '$EXTRA_PRO_BACKEND_STATUS' extra: '$raw'. " +
                        "Use $USE_ACTUAL | never | active | expired."
                )
                return false
            }
        }

        // Written through the specific setters, not setStringPreference: these emit on
        // TextSecurePreferences.events, which is what ProStatusManager.proDataState collects. A generic
        // write would persist the value and emit nothing, so the mock would appear not to apply until
        // the next launch.
        prefs.setForceCurrentUserAsPro(mocked != null)
        prefs.setDebugSubscriptionType(mocked)
        Log.i(TAG, "Set mocked Pro state to '$raw' (debug subscription = ${mocked?.name ?: "off"})")
        return true
    }

    /** Sets the mocked Pro access expiry. See [EXTRA_PRO_ACCESS_EXPIRY] for the accepted forms. */
    private fun applyProAccessExpiry(intent: Intent, prefs: TextSecurePreferences): Boolean {
        if (!intent.hasExtra(EXTRA_PRO_ACCESS_EXPIRY)) {
            return false
        }

        val raw = intent.getStringExtra(EXTRA_PRO_ACCESS_EXPIRY).orEmpty().trim()

        // Deliberately distinguishes "absent" from the explicit-clear sentinel, as the other keys do.
        if (raw.equals(USE_ACTUAL, ignoreCase = true)) {
            if (prefs.getDebugProAccessExpiry() == null) {
                return false
            }
            Log.i(TAG, "Clearing mocked Pro access expiry")
            prefs.setDebugProAccessExpiry(null)
            return true
        }

        val parsed = parseExpiry(raw)
        if (parsed == null) {
            Log.e(
                TAG,
                "Ignoring unparseable '$EXTRA_PRO_ACCESS_EXPIRY' extra: '$raw'. " +
                    "Use epoch SECONDS, +<n>[smhd], or $USE_ACTUAL."
            )
            return false
        }

        // Bounded rather than trusted: see EXTRA_PRO_ACCESS_EXPIRY on why a unit slip must be loud.
        val now = Instant.now()
        val limit = Duration.ofDays(MAX_EXPIRY_SKEW_YEARS * 365)
        if (parsed.isBefore(now - limit) || parsed.isAfter(now + limit)) {
            Log.e(
                TAG,
                "Ignoring '$EXTRA_PRO_ACCESS_EXPIRY' extra: '$raw' resolves to $parsed, more than " +
                    "$MAX_EXPIRY_SKEW_YEARS years from now. Epoch MILLISECONDS passed where SECONDS " +
                    "are expected is the usual cause."
            )
            return false
        }

        Log.i(TAG, "Setting mocked Pro access expiry to $parsed")
        prefs.setDebugProAccessExpiry(parsed)
        return true
    }

    /** `+<n><unit>` relative, or bare epoch milliseconds. Null when neither parses. */
    private fun parseExpiry(raw: String): Instant? {
        if (raw.startsWith("+")) {
            val body = raw.substring(1)
            val amount = body.dropLast(1).toLongOrNull() ?: return null
            val duration = when (body.lastOrNull()?.lowercaseChar()) {
                's' -> Duration.ofSeconds(amount)
                'm' -> Duration.ofMinutes(amount)
                'h' -> Duration.ofHours(amount)
                'd' -> Duration.ofDays(amount)
                else -> return null
            }
            return Instant.now() + duration
        }

        return raw.toLongOrNull()?.let(Instant::ofEpochSecond)
    }

    /** Sets the mocked load state of the Pro settings screen. See [EXTRA_PRO_LOADING_STATE]. */
    private fun applyProLoadingState(intent: Intent, prefs: TextSecurePreferences): Boolean {
        if (!intent.hasExtra(EXTRA_PRO_LOADING_STATE)) {
            return false
        }

        val raw = intent.getStringExtra(EXTRA_PRO_LOADING_STATE).orEmpty().trim()
        val mocked: DebugMenuViewModel.DebugProPlanStatus? = when (raw.lowercase()) {
            USE_ACTUAL -> null
            "loading" -> DebugMenuViewModel.DebugProPlanStatus.LOADING
            "error" -> DebugMenuViewModel.DebugProPlanStatus.ERROR
            "success" -> DebugMenuViewModel.DebugProPlanStatus.NORMAL
            else -> {
                Log.e(
                    TAG,
                    "Ignoring unknown '$EXTRA_PRO_LOADING_STATE' extra: '$raw'. " +
                        "Use $USE_ACTUAL | loading | error | success."
                )
                return false
            }
        }

        prefs.setDebugProPlanStatus(mocked)
        Log.i(TAG, "Set mocked Pro load state to '$raw' (${mocked?.name ?: "off"})")
        return true
    }

}
