package org.thoughtcrime.securesms.pro

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import network.loki.messenger.BuildConfig
import network.loki.messenger.libsession_util.pro.BackendRequests
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log

@Module
@InstallIn(SingletonComponent::class)
class ProModule {
    @Provides
    fun provideProBackendConfig(prefs: TextSecurePreferences): ProBackendConfig {
        // The backend URL + Ed25519 signing pubkey come from libsession (single source of truth), so a
        // future change happens in exactly one place rather than a per-client copy. x25519 is derived
        // on the fly from the Ed key (see ProBackendConfig).
        val compiledIn = ProBackendConfig(
            url = BackendRequests.proBackendUrl(),
            ed25519PubKeyHex = BackendRequests.proBackendPubKeyHex(),
        )

        return qaBackendOverride(prefs) ?: compiledIn
    }

    /**
     * A QA backend supplied as a launch extra (see `QaLaunchConfig`), or `null` for none.
     *
     * Gated on the same compile-time flag as the reader that writes the preference, so a release build
     * cannot be repointed even if the preference were somehow populated. The launcher is an exported
     * activity-alias, so this stays defence-in-depth rather than trusting the write path alone.
     *
     * Re-validated here rather than trusted from the preference: this builds the config used for every
     * Pro request, and `ProBackendConfig` throws on a malformed URL or a bad-length key. Falling back
     * to the compiled-in backend is the safe failure, so a bad value degrades rather than taking the
     * app down during dependency-graph construction.
     */
    private fun qaBackendOverride(prefs: TextSecurePreferences): ProBackendConfig? {
        if (!BuildConfig.ALLOW_QA_LAUNCH_CONFIG) {
            return null
        }

        val url = prefs.getProBackendUrl()?.takeIf { it.isNotBlank() } ?: return null
        val pubkey = prefs.getProBackendPubkey()?.takeIf { it.isNotBlank() } ?: return null

        val parsed = url.toHttpUrlOrNull()
        if (parsed == null) {
            Log.e(TAG, "Ignoring malformed Pro backend override URL: '$url'")
            return null
        }

        return try {
            ProBackendConfig(url = parsed, ed25519PubKeyHex = pubkey).also {
                Log.i(TAG, "Using Pro backend override: $parsed")
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Ignoring unusable Pro backend override", e)
            null
        }
    }

    private companion object {
        private const val TAG = "ProModule"
    }
}
