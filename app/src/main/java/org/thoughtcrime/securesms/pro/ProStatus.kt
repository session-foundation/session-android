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
         * Entitled, with NO plan detail known — the DISPLAY seed, derived from a local proof when no
         * `get_pro_status` response has ever been persisted.
         *
         * Carries no date DELIBERATELY, and that is the point of it being its own type rather than a
         * dated variant holding a sentinel. `E`, `G` and the provider are response-owned: only a response
         * carries `latest_payment` and the values that must agree with it, and the proof's own expiry is a
         * short clamped credential lifetime rather than the plan's payment-due date — on a compressed QA
         * clock they differ by ~300s against ~30 days. Seeding a date from the proof would therefore show
         * an account paid through a month as expiring in minutes.
         *
         * Because it cannot HOLD a date, no reader can render one. That is why this is a type and not a
         * nullable field: `ProSettingsViewModel` already floors a negative remaining duration to zero and
         * renders "0 seconds" instead of "Expired", which is what discipline alone achieves here.
         *
         * `is Active` still matches, so every "are we Pro for display purposes" check keeps working. Code
         * that needs the plan's dates must narrow to [WithPlan], and the compiler will say so.
         */
        data object FromProof: Active

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

    data class Expired(
        /**
         * The payment-due date, as the backend sends it. This is the date to display; coverage ran a
         * further [gracePeriod] past it.
         */
        val expiredAt: Instant,
        val gracePeriod: Duration,
        val providerData: PaymentProviderMetadata
    ): ProStatus {
        /**
         * When access actually ended, and the anchor for any window measuring how long ago that was.
         *
         * The backend only reports EXPIRED once coverage has ended, so a window measured from
         * [expiredAt] instead is short by exactly [gracePeriod], and empty once grace reaches the
         * window length. [gracePeriod] is coverage-past-expiry: the provider's dunning window plus the
         * backend's ~1h renewal-latency allowance. It is multi-day once a real dunning window is known
         * (Apple states its retry window directly; for Play the backend does NOT follow Play's expiry
         * extension — it keeps the reported expiry at the original payment-due instant and carries the
         * extension as the grace instead), and ~1h before then.
         *
         * Derived here rather than at the consumer so a second reader cannot pick the other anchor.
         */
        val coverageEndedAt: Instant get() = expiredAt.plus(gracePeriod)
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