package org.thoughtcrime.securesms.pro

import network.loki.messenger.libsession_util.pro.BackendRequests

object ProUrls {
    val FAQ: String get() = BackendRequests.proUrls().faq
    val PRIVACY_POLICY: String get() = BackendRequests.proUrls().privacyPolicy
    val ROADMAP: String get() = BackendRequests.proUrls().roadmap
    val SUPPORT: String get() = BackendRequests.proUrls().support
    val TERMS_OF_SERVICE: String get() = BackendRequests.proUrls().termsOfService
}
