package org.thoughtcrime.securesms.pro

import network.loki.messenger.BuildConfig
import network.loki.messenger.libsession_util.protocol.PaymentProviderMetadata
import org.thoughtcrime.securesms.pro.subscription.ProSubscriptionDuration
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.State
import java.time.Instant
import java.time.ZoneId

sealed interface ProStatus{
    data object NeverSubscribed: ProStatus

    sealed interface Active: ProStatus{
        val validUntil: Instant
        val duration: ProSubscriptionDuration
        val providerData: PaymentProviderMetadata
        val quickRefundExpiry: Instant?
        val refundInProgress: Boolean

        data class AutoRenewing(
            override val validUntil: Instant,
            override val duration: ProSubscriptionDuration,
            override val providerData: PaymentProviderMetadata,
            override val quickRefundExpiry: Instant?,
            override val refundInProgress: Boolean
        ): Active

        data class Expiring(
            override val validUntil: Instant,
            override val duration: ProSubscriptionDuration,
            override val providerData: PaymentProviderMetadata,
            override val quickRefundExpiry: Instant?,
            override val refundInProgress: Boolean
        ): Active

        fun isWithinQuickRefundWindow(): Boolean {
            return quickRefundExpiry != null && quickRefundExpiry!!.isAfter(Instant.now())
        }

        fun validUntilFormatted(): String {
            val pattern = if (BuildConfig.BUILD_TYPE != "release")
                "MMMM d, yyyy, h:mm a" // non prod builds can show seconds for debugging purposes
            else "MMMM d, yyyy"
            return DateUtils.getLocaleFormattedDate(
                validUntil.toEpochMilli(), pattern
            )
        }
    }

    data class Expired(
        val expiredAt: Instant,
        val providerData: PaymentProviderMetadata
    ): ProStatus
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