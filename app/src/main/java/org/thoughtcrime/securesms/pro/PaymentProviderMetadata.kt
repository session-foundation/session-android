package org.thoughtcrime.securesms.pro

import android.content.Context
import com.squareup.phrase.Phrase
import network.loki.messenger.libsession_util.pro.BackendRequests

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
 * Build [PaymentProviderMetadata] for an opaque provider slug: display strings resolved dynamically from
 * `pro_provider_<slug>_<suffix>` resources (so a new provider needs only translations, not a code change),
 * URLs from libsession.
 */
fun providerMetadata(providerSlug: String, context: Context): PaymentProviderMetadata {
    val urls = BackendRequests.providerUrls(providerSlug)
    return PaymentProviderMetadata(
        device = providerDisplay(context, providerSlug, "device"),
        store = providerDisplay(context, providerSlug, "store"),
        platform = providerDisplay(context, providerSlug, "platform"),
        platformAccount = providerDisplay(context, providerSlug, "account"),
        refundPlatformUrl = urls?.refundPlatformUrl.orEmpty(),
        refundSupportUrl = urls?.refundSupportUrl.orEmpty(),
        refundStatusUrl = urls?.refundStatusUrl.orEmpty(),
        updateSubscriptionUrl = urls?.updateSubscriptionUrl.orEmpty(),
        cancelSubscriptionUrl = urls?.cancelSubscriptionUrl.orEmpty(),
    )
}

/**
 * Resolve one provider display field for [slug]:
 *  1. `pro_provider_<slug>_<suffix>` if that string resource exists;
 *  2. else `pro_provider_unknown_<suffix>`, substituting the raw slug for `{provider}` when the template
 *     carries it (forward-compatible: an unknown/untranslated provider still renders sanely);
 *  3. else the raw slug (last resort — never throws).
 */
private fun providerDisplay(context: Context, slug: String, suffix: String): String {
    val res = context.resources
    val pkg = context.packageName

    val specific = res.getIdentifier("pro_provider_${slug}_$suffix", "string", pkg)
    if (specific != 0) return context.getString(specific)

    val unknown = res.getIdentifier("pro_provider_unknown_$suffix", "string", pkg)
    if (unknown != 0) {
        return runCatching {
            val pattern = res.getString(unknown)
            val phrase = Phrase.from(context, unknown)
            // Phrase is strict — only put {provider} when the template actually contains it.
            if (pattern.contains("{provider}")) phrase.put("provider", slug)
            phrase.format().toString()
        }.getOrDefault(slug)
    }

    return slug
}

/** Localized store name for a provider slug (`pro_provider_<slug>_store`), with the unknown/slug fallback. */
fun providerStoreName(providerSlug: String, context: Context): String =
    providerDisplay(context, providerSlug, "store")

/**
 * Build the `{pro_stores}` bulleted list of purchasable stores: the visible provider slugs from
 * libsession (this platform's own — Google Play — hoisted first, the rest keeping libsession's order),
 * keeping only those with a `pro_provider_<slug>_store` translation (an unknown/untranslated provider is
 * skipped). Joined with `\n• ` — android renders line breaks as `\n` (the pipeline converts `<br/>`→`\n`).
 */
fun buildProStoresList(context: Context): String {
    val slugs = BackendRequests.visiblePlatforms().toMutableList()
    val ownIndex = slugs.indexOf(BackendRequests.PAYMENT_PROVIDER_GOOGLE_PLAY)
    if (ownIndex > 0) {
        slugs.add(0, slugs.removeAt(ownIndex))
    }
    return slugs
        .mapNotNull { slug ->
            val resId = context.resources.getIdentifier("pro_provider_${slug}_store", "string", context.packageName)
            if (resId != 0) context.getString(resId) else null
        }
        .joinToString(separator = "") { "\n• $it" }
}
