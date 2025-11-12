package org.thoughtcrime.securesms.pro.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.PaymentProvider
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.serializable.InstantAsMillisSerializer
import java.time.Instant

class GetProStatusRequest @AssistedInject constructor(
    private val snodeClock: SnodeClock,
    @Assisted private val masterPrivateKey: ByteArray,
) : ApiRequest<Int, GetProStatusResponse> {
    override val endpoint: String
        get() = "get_pro_status"

    override fun buildJsonBody(): String {
        return BackendRequests.buildGetProStatusRequestJson(
            version = 0,
            proMasterPrivateKey = masterPrivateKey,
            nowMs = snodeClock.currentTimeMills(),
            count = 10,
        )
    }

    override val responseDeserializer: DeserializationStrategy<GetProStatusResponse>
        get() = GetProStatusResponse.serializer()

    override fun convertStatus(status: Int): Int = status

    @AssistedFactory
    interface Factory {
        fun create(masterPrivateKey: ByteArray): GetProStatusRequest
    }
}

typealias GetProStatusResponseStatus = Int

@Serializable
class GetProStatusResponse(
    val status: GetProStatusResponseStatus,

    @SerialName("auto_renewing")
    val autoRenewing: Boolean,

    @SerialName("expiry_unix_ts_ms")
    @Serializable(with = InstantAsMillisSerializer::class)
    val expiry: Instant,

    @SerialName("grace_duration_ms")
    val graceDurationMs: Long,

    @SerialName("error_report")
    val errorReport: Int? = null,

    @SerialName("payments_total")
    val paymentsTotal: Int? = null,

    @SerialName("items")
    val paymentItems: List<Item> = emptyList(),

    val version: Int,
) {

    @Serializable
    data class Item(
        val status: GetProStatusResponseStatus,

        @SerialName("payment_provider")
        val paymentProvider: PaymentProvider,

        @SerialName("expiry_unix_ts_ms")
        @Serializable(with = InstantAsMillisSerializer::class)
        val expiry: Instant? = null,

        @SerialName("grace_duration_ms")
        val graceDurationMs: Long,

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
        const val STATUS_NEVER_BEEN_PRO: GetProStatusResponseStatus = 0
        const val STATUS_ACTIVE: GetProStatusResponseStatus = 1
        const val STATUS_EXPIRED: GetProStatusResponseStatus = 2
    }
}