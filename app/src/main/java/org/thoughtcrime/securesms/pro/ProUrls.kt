package org.thoughtcrime.securesms.pro

/**
 * The Pro destinations, mirroring libsession's URL registry
 * (`session_protocol.cpp`, the `url_pro_*` fields).
 *
 * Copies rather than reads: the registry is a C struct of `const char*` with no accessor exposed to
 * Kotlin, so consuming it directly would mean adding JNI surface for five constants.
 *
 * Being copies, they can drift from it, and they have — every value here was once wrong at the use site
 * that needed it. They are defined together so that comparing this file against the registry is the whole
 * check; correcting a single link where it happens to be used is what let them diverge one at a time.
 */
object ProUrls {
    const val FAQ = "https://getsession.org/pro#faq"
    /**
     * The refund route while the store's own quick-refund window is open. A Session-owned short link
     * that redirects to the store, so the destination can change without a client release - which is
     * why the CTA beside it names the store while this url does not.
     */
    const val QUICK_REFUND = "https://getsession.org/android-refund"
    const val PRIVACY_POLICY = "https://getsession.org/pro-privacy"
    const val ROADMAP = "https://getsession.org/pro#roadmap"
    const val SUPPORT = "https://getsession.org/pro-support"
    const val TERMS_OF_SERVICE = "https://getsession.org/pro-terms"
}
