package org.thoughtcrime.securesms.pro

import network.loki.messenger.BuildConfig
import org.thoughtcrime.securesms.pro.subscription.ProPlanPeriod
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.State
import java.time.Duration
import java.time.Instant

sealed interface ProStatus{
    data object NeverSubscribed: ProStatus

    sealed interface Active: ProStatus{

        /**
         * Entitled, with no plan detail known: derived from synced config before any response has
         * settled the dates.
         *
         * Holds no date, and cannot, so nothing downstream can render one from it. The plan's dates and
         * the provider metadata a rendered date must agree with are settled together by a
         * `get_pro_status` response. The proof's own expiry is not a substitute: it is a clamped
         * credential lifetime rather than the plan's payment-due date, and the two diverge by orders of
         * magnitude under a compressed clock.
         *
         * `is Active` matches, so entitlement checks need no narrowing. Reading a date requires
         * narrowing to [WithPlan].
         */
        data object FromLocalState: Active

        /**
         * Active WITH plan detail, i.e. sourced from a `get_pro_status` response.
         *
         * Everything a response owns lives here rather than on [Active], so a reader that wants a date has
         * to prove it has one.
         */
        sealed interface WithPlan: Active {
            val renewingAt: Instant // the payment/renewal-due date (E), as the backend sends it
            val duration: ProPlanPeriod  // the backend's raw (count, unit) — rendered generically, never bucketed
            val providerData: PaymentProviderMetadata
            val quickRefundExpiry: Instant?
            val refundInProgress: Boolean

            /**
             * Whether the store's own quick-refund window is still open, which decides between the
             * <48h (#19/#22) and >48h (#20/#23) refund screens.
             *
             * [now] must come from [org.session.libsession.network.SnodeClock], as everywhere else in
             * the Pro stack — `quickRefundExpiry` is a backend/store timestamp, so comparing it against
             * the device clock lets clock skew flip the branch.
             */
            fun isWithinQuickRefundWindow(now: Instant): Boolean {
                return quickRefundExpiry?.isAfter(now) == true
            }

            fun renewingAtFormatted(): String {
                val pattern = if (BuildConfig.BUILD_TYPE != "release")
                    "MMMM d, yyyy, h:mm a" // non prod builds can show seconds for debugging purposes
                else "MMMM d, yyyy"
                return DateUtils.getLocaleFormattedDate(
                    renewingAt.toEpochMilli(), pattern
                )
            }
        }

        data class AutoRenewing(
            override val renewingAt: Instant,
            override val duration: ProPlanPeriod,
            override val providerData: PaymentProviderMetadata,
            override val quickRefundExpiry: Instant?,
            override val refundInProgress: Boolean,
            val inGracePeriod: Boolean
        ): WithPlan

        data class Expiring(
            override val renewingAt: Instant,
            override val duration: ProPlanPeriod,
            override val providerData: PaymentProviderMetadata,
            override val quickRefundExpiry: Instant?,
            override val refundInProgress: Boolean,
        ): WithPlan

    }

    sealed interface Expired: ProStatus {

        /**
         * Expired with no plan detail: derived from local state before any response has settled the
         * dates.
         *
         * Carries no date, so no window can be measured from it and no reader can render one. The dates
         * a window needs are response-owned, and the alternative — an epoch sentinel — silently produces
         * a window that has already elapsed, which reads as "no CTA is due" rather than as missing data.
         */
        data object FromLocalState: Expired

        /** Expired with the payment dates a `get_pro_status` response carries. */
        data class WithPlan(
            /**
             * The payment-due date, as the backend sends it. This is the date to display; coverage ran a
             * further [gracePeriod] past it.
             */
            val expiredAt: Instant,
            val gracePeriod: Duration,
            val providerData: PaymentProviderMetadata
        ): Expired {
            /**
             * When access actually ended, and the anchor for any window measuring how long ago that was.
             *
             * The backend only reports EXPIRED once coverage has ended, so a window measured from
             * [expiredAt] instead is short by exactly [gracePeriod], and empty once grace reaches the
             * window length. [gracePeriod] is coverage-past-expiry: the provider's dunning window plus
             * the backend's ~1h renewal-latency allowance. It is multi-day once a real dunning window is
             * known (Apple states its retry window directly; for Play the backend does NOT follow Play's
             * expiry extension — it keeps the reported expiry at the original payment-due instant and
             * carries the extension as the grace instead), and ~1h before then.
             *
             * Derived here rather than at the consumer so a second reader cannot pick the other anchor.
             */
            val coverageEndedAt: Instant get() = expiredAt.plus(gracePeriod)
        }
    }
}

data class ProDataState(
    val type: ProStatus,
    val showProBadge: Boolean,
    val refreshState: State<Unit>,
)

fun getDefaultSubscriptionStateData() = ProDataState(
    type = ProStatus.NeverSubscribed,
    refreshState = State.Loading,
    showProBadge = false
)