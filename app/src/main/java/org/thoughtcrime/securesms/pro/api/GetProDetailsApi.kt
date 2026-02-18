package org.thoughtcrime.securesms.pro.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.PaymentProvider
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.serializable.InstantAsMillisSerializer
import java.time.Instant

class GetProDetailsApi @AssistedInject constructor(
    private val snodeClock: SnodeClock,
    @Assisted private val masterPrivateKey: ByteArray,
    deps: ProApiDependencies,
) : ProApi<Int, ProDetails>(deps) {
    override val endpoint: String
        get() = "get_pro_details"

    override fun buildJsonBody(): String {
        return BackendRequests.buildGetProDetailsRequestJson(
            version = 0,
            proMasterPrivateKey = masterPrivateKey,
            nowMs = snodeClock.currentTimeMillis(),
            count = 10,
        )
    }

    override val responseDeserializer: DeserializationStrategy<ProDetails>
        get() = ProDetails.serializer()

    override fun convertErrorStatus(status: Int): Int = status

    @AssistedFactory
    interface Factory {
        fun create(masterPrivateKey: ByteArray): GetProDetailsApi
    }
}

typealias ServerProDetailsStatus = Int
typealias ServerPlanDuration = Int

@Serializable
class ProDetails(
    val status: ServerProDetailsStatus,

    @SerialName("auto_renewing")
    val autoRenewing: Boolean? = null,

    @SerialName("expiry_unix_ts_ms")
    @Serializable(with = InstantAsMillisSerializer::class)
    val expiry: Instant? = null,

    @SerialName("grace_period_duration_ms")
    val graceDurationMs: Long? = null,

    @SerialName("error_report")
    val errorReport: Int? = null,

    @SerialName("payments_total")
    val paymentsTotal: Int? = null,

    @SerialName("items")
    val paymentItems: List<Item> = emptyList(),

    @SerialName("refund_requested_unix_ts_ms")
    val refundRequestedAtMs: Long = 0,



    val version: Int,
) {
    init {
        check((status != DETAILS_STATUS_ACTIVE && status != DETAILS_STATUS_EXPIRED) || expiry != null) { "Expiry must not be null for state other than 'never subscribed'" }
        check((status != DETAILS_STATUS_ACTIVE && status != DETAILS_STATUS_EXPIRED) || paymentItems.isNotEmpty()) { "Can't have no payment items for state other than 'never subscribed'" }
    }

    @Serializable
    data class Item(
        @SerialName("plan")
        val planDuration: ServerPlanDuration,

        val status: Int, // Payment status [Redeemed, Revoked, Expired] - we do not use this status in the clients

        @SerialName("payment_provider")
        val paymentProvider: PaymentProvider,

        @SerialName("expiry_unix_ts_ms")
        @Serializable(with = InstantAsMillisSerializer::class)
        val expiry: Instant? = null,

        @SerialName("grace_period_duration_ms")
        val graceDurationMs: Long? = null,

        @SerialName("platform_refund_expiry_unix_ts_ms")
        @Serializable(with = InstantAsMillisSerializer::class)
        val platformExpiry: Instant? = null,

        @SerialName("redeemed_unix_ts_ms")
        @Serializable(with = InstantAsMillisSerializer::class)
        val timeRedeemed: Instant? = null,

        @SerialName("unredeemed_unix_ts_ms")
        @Serializable(with = InstantAsMillisSerializer::class)
        val timeUnredeemed: Instant? = null,

        @SerialName("revoked_unix_ts_ms")
        @Serializable(with = InstantAsMillisSerializer::class)
        val timeRevoked: Instant? = null,

        @SerialName("google_order_id")
        val googleOrderId: String? = null,

        @SerialName("google_payment_token")
        val googlePaymentToken: String? = null,

        @SerialName("apple_original_tx_id")
        val appleOriginalTxId: String? = null,

        @SerialName("apple_tx_id")
        val appleTxId: String? = null,

        @SerialName("apple_web_line_order_id")
        val appleWebLineOrderId: String? = null,
    )

    companion object {
        const val DETAILS_STATUS_NEVER_BEEN_PRO: ServerProDetailsStatus = 0
        const val DETAILS_STATUS_ACTIVE: ServerProDetailsStatus = 1
        const val DETAILS_STATUS_EXPIRED: ServerProDetailsStatus = 2

        const val SERVER_PLAN_DURATION_1_MONTH: ServerPlanDuration = 1
        const val SERVER_PLAN_DURATION_3_MONTH: ServerPlanDuration = 2
        const val SERVER_PLAN_DURATION_12_MONTH: ServerPlanDuration = 3
    }
}