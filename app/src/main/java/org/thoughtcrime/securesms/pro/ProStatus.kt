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
        val renewingAt: Instant //this takes into account the expiry and the grace period
        val duration: ProPlanPeriod  // the backend's raw (count, unit) — rendered generically, never bucketed
        val providerData: PaymentProviderMetadata
        val quickRefundExpiry: Instant?
        val refundInProgress: Boolean

        data class AutoRenewing(
            override val renewingAt: Instant,
            override val duration: ProPlanPeriod,
            override val providerData: PaymentProviderMetadata,
            override val quickRefundExpiry: Instant?,
            override val refundInProgress: Boolean,
            val inGracePeriod: Boolean
        ): Active

        data class Expiring(
            override val renewingAt: Instant,
            override val duration: ProPlanPeriod,
            override val providerData: PaymentProviderMetadata,
            override val quickRefundExpiry: Instant?,
            override val refundInProgress: Boolean,
        ): Active

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
         * When access actually ended, and the anchor for anything measuring how long ago that was.
         *
         * The backend only reports EXPIRED once coverage has ended, so measuring an "expired
         * recently" window from [expiredAt] instead shortens it by exactly [gracePeriod] — and
         * empties it when the grace period is at least as long as the window.
         *
         * [gracePeriod] is multi-day for an Apple account whose dunning window ran out, because Apple
         * states its retry window separately and it arrives here as grace. It is only the ~1h
         * renewal-latency allowance on Play, which folds grace into the expiry it reports, and zero for
         * an account that is not renewing at all — for those two the anchors effectively coincide.
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