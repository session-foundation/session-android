package org.thoughtcrime.securesms.pro

import android.content.Context
import network.loki.messenger.libsession_util.pro.BackendRequests.PAYMENT_PROVIDER_APP_STORE
import network.loki.messenger.libsession_util.pro.BackendRequests.PAYMENT_PROVIDER_GOOGLE_PLAY
import network.loki.messenger.libsession_util.pro.GetProStatusResponse
import network.loki.messenger.libsession_util.pro.ProPaymentItem
import org.thoughtcrime.securesms.pro.subscription.ProPlanPeriod
import org.thoughtcrime.securesms.pro.subscription.ProPlanUnit
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import java.time.Duration
import java.time.Instant

/**
 * Account-level Pro status slugs (get-pro-status `user_status`, spec §5.2). Closed set on the backend:
 * `never`/`active`/`expired` — note there is NO account-level `revoked` (that's a per-item payment
 * status / an error_code). Opaque on the wire, so an unrecognized value is treated as "not subscribed".
 */
object ProUserStatus {
    const val NEVER = "never"
    const val ACTIVE = "active"
    const val EXPIRED = "expired"
}

/**
 * Map a libsession-parsed get-pro-status response to the app's [ProStatus] domain model. Needs a [Context]
 * to resolve the (client-owned) provider display strings.
 */
/**
 * @param confirmedAt when the fetch behind this response COMPLETED, or null if nothing has been
 *   confirmed. Used to gate [ProStatus.Active.AutoRenewing.inGracePeriod]: a snapshot taken before the
 *   renewal fell due cannot have observed it failing, so raising the "renewal unsuccessful" warning off
 *   one turns an ordinary boundary crossing into an alarm the backend never reported.
 *
 *   Gated **here**, where the flag is produced, rather than at each consumer. Five call sites read it
 *   today; a sixth would inherit no protection if the check lived in front of the value instead of
 *   inside it — and there would be nothing to tell whoever added it.
 */
fun GetProStatusResponse.toProStatus(
    nowMs: Long,
    context: Context,
    refundInProgress: Boolean,
    confirmedAt: Instant?,
): ProStatus {
    return when (userStatus) {
        ProUserStatus.ACTIVE -> {
            val paymentItem = latestPayment ?: return ProStatus.NeverSubscribed
            // `expiry` is COVERAGE END, not the paid-through date: the backend folds grace into it
            // before sending it (`Session-Pro-Backend` `backend.py` `_lookup_user_expiry`:
            // `payment_expiry_at = expiry_at + grace if auto_renewing else expiry_at` ->
            // `users.expiry_at` -> the wire `expiry_ts`, `server.py:317`), and judges `active` against
            // that same value (`:322`). Its own test subtracts grace from the wire value to recover
            // the store's date (`tests/test_google.py:556-560`).
            //
            // So the renewal is due at `expiry - gracePeriod`, and the grace window is
            // `expiry - gracePeriod <= now < expiry`. Subtracting is unconditional and needs no
            // provider branching: the wire sends grace = 0 whenever the subscription is not
            // auto-renewing (`server.py:335`), so `expiry - 0 == expiry` for those accounts.
            //
            // This was wrong in the opposite direction until 2026-08-10 — it treated `expiry` as
            // paid-through and never subtracted, which made `inGracePeriod` unreachable (it sits in a
            // branch requiring `now <= expiry`) and rendered the renewal date a whole grace period
            // late. Not cosmetic where it mattered: Apple's grace is the backend's own ~1h stand-in,
            // but Google's is the operator-configured base-plan value in DAYS, fetched exactly when
            // the subscriber enters grace — so the date was days wrong on the screen whose purpose is
            // that date, and most wrong precisely when someone was looking at it.
            val coverageEnd = expiry ?: return ProStatus.NeverSubscribed
            // The paid-through end — when the renewal actually falls due.
            val renewingAt = coverageEnd.minus(gracePeriod)
            val renewingAtMs = renewingAt.toEpochMilli()
            val providerData = providerMetadata(paymentItem.paymentProvider, context)
            val duration = paymentItem.toProPlanPeriod()

            // Correctness guard (plan grammar, §1): a lifetime plan is NOT a renewing/expiring subscription and
            // has no renewal/expiry date to render. A genuine lifetime carries no account `expiry`, so it
            // already falls through the `expiry ?: return NeverSubscribed` short-circuit above; this
            // explicit check additionally guarantees a lifetime plan can never be presented via the
            // AutoRenewing/Expiring ("renews/expires on {date}") paths. NOTE: there is no dedicated
            // always-on Active state yet, so lifetime is currently surfaced as not-subscribed — a
            // follow-up should add an `Active.Lifetime` state with no date.
            if (duration.isLifetime) return ProStatus.NeverSubscribed

            if (autoRenewing) {
                ProStatus.Active.AutoRenewing(
                    renewingAt = renewingAt,
                    duration = duration,
                    providerData = providerData,
                    quickRefundExpiry = paymentItem.platformRefundExpiry,
                    refundInProgress = refundInProgress,
                    // Covered but past the paid-through end = the renewal is overdue and grace is
                    // running. Reachable now that renewingAt is `coverageEnd - grace` rather than
                    // `coverageEnd`, which no `now` in this branch could ever be at or past.
                    //
                    // Requires a fetch that COMPLETED at or after the renewal fell due — see
                    // `confirmedAt`. Never constructed true on an unconfirmed snapshot.
                    inGracePeriod = nowMs >= renewingAtMs &&
                        confirmedAt != null && !confirmedAt.isBefore(renewingAt),
                )
            } else {
                ProStatus.Active.Expiring(
                    renewingAt = renewingAt, // the paid-through end (not auto-renewing → it just expires then)
                    duration = duration,
                    providerData = providerData,
                    quickRefundExpiry = paymentItem.platformRefundExpiry,
                    refundInProgress = refundInProgress,
                )
            }
        }

        ProUserStatus.EXPIRED -> ProStatus.Expired(
            expiredAt = expiry ?: Instant.EPOCH,
            providerData = providerMetadata(
                latestPayment?.paymentProvider ?: PAYMENT_PROVIDER_GOOGLE_PLAY,
                context,
            ),
        )

        // "never" + any unrecognized/future slug -> treat as not subscribed.
        else -> ProStatus.NeverSubscribed
    }
}

/**
 * The billing period as a (count, unit) [ProPlanPeriod]. libsession parses the wire `plan` grammar
 * (pro-wire-protocol.md §1) into `{count, unit}` and the android glue hands it to us as the
 * structured pair `planCount` + `planUnit` (see libsession-util-android `pro_backend.cpp`
 * `plan_unit_to_string`). We keep it as (count, unit) verbatim — the unit is PRESERVED as transmitted
 * (never canonicalized), so a NEW period ("6m", "1w", "2y") needs ZERO code change to render. An
 * unrecognized unit name shouldn't occur (closed grammar); we fall back to a one-month period.
 */
fun ProPaymentItem.toProPlanPeriod(): ProPlanPeriod {
    val unit = ProPlanUnit.fromWireName(planUnit) ?: ProPlanUnit.MONTH
    return ProPlanPeriod(planCount, unit)
}

/**
 * Whether the subscription was bought somewhere this device can't manage it — i.e. anywhere other
 * than Google Play. Drives the three non-originating screens (Update #7, Cancel #27, Refund
 * #22/#23) and price suppression.
 *
 * Keyed off the provider **slug**, never off a display field: those are localized, so comparing
 * them would classify every non-English Google Play subscriber as non-originating.
 */
fun PaymentProviderMetadata.isFromAnotherPlatform(): Boolean {
    return slug != PAYMENT_PROVIDER_GOOGLE_PLAY
}

/**
 * Some UI cases require a special display name for the platform: for our own store the copy reads
 * better with the store name ("Google Play") than the platform name ("Google").
 */
fun PaymentProviderMetadata.getPlatformDisplayName(): String {
    return if (slug == PAYMENT_PROVIDER_GOOGLE_PLAY) store else platform
}

/**
 * Preview Data - Reusable data for composable previews
 */

val previewAppleMetaData = PaymentProviderMetadata(
    slug = PAYMENT_PROVIDER_APP_STORE,
    device = "iOS",
    store = "Apple App Store",
    platform = "Apple",
    platformAccount = "Apple Account",
    updateSubscriptionUrl = "https://www.apple.com/account/subscriptions",
    cancelSubscriptionUrl = "https://www.apple.com/account/subscriptions",
    refundPlatformUrl = "https://www.apple.com/account/subscriptions",
    refundSupportUrl = "https://www.apple.com/account/subscriptions",
    refundStatusUrl = "https://www.apple.com/account/subscriptions"
)

val previewAutoRenewingApple = ProStatus.Active.AutoRenewing(
    renewingAt = Instant.now() + Duration.ofDays(14),
    duration = ProSubscriptionDuration.THREE_MONTHS.period,
    providerData = previewAppleMetaData,
    quickRefundExpiry = Instant.now() + Duration.ofDays(14),
    refundInProgress = false,
    inGracePeriod = false
)

val previewExpiredApple = ProStatus.Expired(
    expiredAt = Instant.now() - Duration.ofDays(14),
    providerData = previewAppleMetaData
)
