package org.thoughtcrime.securesms.pro

import network.loki.messenger.libsession_util.pro.BackendRequests

/**
 * The Pro destinations, from libsession's URL registry (`session_protocol.cpp`, the `url_pro_*` fields).
 *
 * These were copies, because the registry is a C struct of `const char*` with no accessor exposed to
 * Kotlin. Being copies they drifted — every value here was once wrong at the use site that needed it.
 * The glue now exposes the registry ([BackendRequests.proUrls]), so a value here should READ it rather
 * than restate it, and the remaining literals below are just the ones not yet converted.
 */
object ProUrls {
    const val FAQ = "https://getsession.org/pro#faq"
    const val PRIVACY_POLICY = "https://getsession.org/pro-privacy"
    const val ROADMAP = "https://getsession.org/pro#roadmap"
    /**
     * Read from libsession rather than copied, now that the glue exposes the registry
     * ([BackendRequests.proUrls]).
     *
     * A getter, not a `val`: the accessor is JNI, so evaluating it while this `object` initialises
     * could run before `System.loadLibrary("session_util")`. Reading per call costs a struct field
     * lookup and cannot be too early.
     */
    val SUPPORT: String get() = BackendRequests.proUrls().support
    const val TERMS_OF_SERVICE = "https://getsession.org/pro-terms"
}
