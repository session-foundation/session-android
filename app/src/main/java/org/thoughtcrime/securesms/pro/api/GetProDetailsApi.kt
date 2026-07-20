package org.thoughtcrime.securesms.pro.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.GetProDetailsResponse
import network.loki.messenger.libsession_util.pro.ProRequest
import org.session.libsession.network.SnodeClock
import org.session.libsession.utilities.serializable.InstantAsSecondsSerializer
import java.time.Instant

class GetProDetailsApi @AssistedInject constructor(
    private val snodeClock: SnodeClock,
    @Assisted private val masterPrivateKey: ByteArray,
    deps: ProApiDependencies,
) : ProApi<GetProDetailsResponse>(deps) {
    override fun buildProRequest(): ProRequest =
        BackendRequests.buildGetProDetailsRequest(
            masterPrivateKey = masterPrivateKey,
            nowSeconds = snodeClock.currentTimeMillis() / 1000,
            count = 10,
        )

    override fun parseResponse(json: String): GetProDetailsResponse =
        BackendRequests.parsePaymentDetailsResponse(json)

    @AssistedFactory
    interface Factory {
        fun create(masterPrivateKey: ByteArray): GetProDetailsApi
    }
}

typealias ServerProDetailsStatus = Int

@Serializable
class ProDetails(
    val status: ServerProDetailsStatus,

    @SerialName("auto_renewing")
    val autoRenewing: Boolean? = null,

    @SerialName("expiry_ts")
    @Serializable(with = InstantAsSecondsSerializer::class)
    val expiry: Instant? = null,

    @SerialName("grace_period_duration")
    val graceDuration: Long? = null,

    @SerialName("error_report")
    val errorReport: Int? = null,

    @SerialName("payments_total")
    val paymentsTotal: Int? = null,

    @SerialName("items")
    val paymentItems: List<Item> = emptyList(),

    @SerialName("refund_requested_ts")
    val refundRequestedAtSeconds: Long = 0,



    val version: Int,
) {
    init {
        check((status != DETAILS_STATUS_ACTIVE && status != DETAILS_STATUS_EXPIRED) || expiry != null) { "Expiry must not be null for state other than 'never subscribed'" }
        check((status != DETAILS_STATUS_ACTIVE && status != DETAILS_STATUS_EXPIRED) || paymentItems.isNotEmpty()) { "Can't have no payment items for state other than 'never subscribed'" }
    }

    @Serializable
    data class Item(
        @SerialName("plan")
        val plan: String, // period code, e.g. "1m" / "3m" / "1y"

        // Payment status code [unredeemed, redeemed, expired, revoked] - we do not use it in the clients
        val status: String? = null,

        @SerialName("payment_provider")
        val paymentProvider: String, // opaque provider slug, e.g. "google_play" / "app_store"

        @SerialName("expiry_ts")
        @Serializable(with = InstantAsSecondsSerializer::class)
        val expiry: Instant? = null,

        @SerialName("grace_period_duration")
        val graceDuration: Long? = null,

        @SerialName("platform_refund_expiry_ts")
        @Serializable(with = InstantAsSecondsSerializer::class)
        val platformExpiry: Instant? = null,

        @SerialName("redeemed_ts")
        @Serializable(with = InstantAsSecondsSerializer::class)
        val timeRedeemed: Instant? = null,

        @SerialName("unredeemed_ts")
        @Serializable(with = InstantAsSecondsSerializer::class)
        val timeUnredeemed: Instant? = null,

        @SerialName("revoked_ts")
        @Serializable(with = InstantAsSecondsSerializer::class)
        val timeRevoked: Instant? = null,

        // Opaque per-provider payment id (Google: "<token>|<order_id>"; others: their id verbatim)
        @SerialName("payment_id")
        val paymentId: String? = null,
    )

    companion object {
        const val DETAILS_STATUS_NEVER_BEEN_PRO: ServerProDetailsStatus = 0
        const val DETAILS_STATUS_ACTIVE: ServerProDetailsStatus = 1
        const val DETAILS_STATUS_EXPIRED: ServerProDetailsStatus = 2
    }
}