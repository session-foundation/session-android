package org.thoughtcrime.securesms.pro

import android.content.Context
import network.loki.messenger.R
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.BackendRequests.PAYMENT_PROVIDER_APP_STORE

/**
 * App-facing per-provider metadata: the human-readable display names — client-owned i18n now that
 * libsession no longer supplies them (Delta #10) — plus the support/management URLs, which are still
 * libsession's (fetched via [BackendRequests.providerUrls] by the provider slug). Replaces the removed
 * libsession `PaymentProviderMetadata` struct.
 */
data class PaymentProviderMetadata(
    val device: String,
    val store: String,
    val platform: String,
    val platformAccount: String,
    val refundPlatformUrl: String,
    val refundSupportUrl: String,
    val refundStatusUrl: String,
    val updateSubscriptionUrl: String,
    val cancelSubscriptionUrl: String,
)

/**
 * Build [PaymentProviderMetadata] for an opaque provider slug: display strings from local resources (the
 * `proProvider*` keys), URLs from libsession.
 *
 * TODO: an unknown/future slug currently falls back to the Google/Play presentation (the common case). A
 * proper fix surfaces a generic "unknown provider (<slug>)" label — needs its own i18n key.
 */
fun providerMetadata(providerSlug: String, context: Context): PaymentProviderMetadata {
    val urls = BackendRequests.providerUrls(providerSlug)
    val isApple = providerSlug == PAYMENT_PROVIDER_APP_STORE
    return PaymentProviderMetadata(
        device = context.getString(
            if (isApple) R.string.proProviderAppleDevice else R.string.proProviderGoogleDevice
        ),
        store = context.getString(
            if (isApple) R.string.proProviderAppleStore else R.string.proProviderGoogleStore
        ),
        platform = context.getString(
            if (isApple) R.string.proProviderApplePlatform else R.string.proProviderGooglePlatform
        ),
        platformAccount = context.getString(
            if (isApple) R.string.proProviderAppleAccount else R.string.proProviderGoogleAccount
        ),
        refundPlatformUrl = urls?.refundPlatformUrl.orEmpty(),
        refundSupportUrl = urls?.refundSupportUrl.orEmpty(),
        refundStatusUrl = urls?.refundStatusUrl.orEmpty(),
        updateSubscriptionUrl = urls?.updateSubscriptionUrl.orEmpty(),
        cancelSubscriptionUrl = urls?.cancelSubscriptionUrl.orEmpty(),
    )
}
