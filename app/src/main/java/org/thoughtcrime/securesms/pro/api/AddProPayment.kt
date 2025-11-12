package org.thoughtcrime.securesms.pro.api

import kotlinx.serialization.DeserializationStrategy
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.ProProof

class AddProPaymentRequest(
    private val googlePaymentToken: String,
    private val googleOrderId: String,
    private val masterPrivateKey: ByteArray,
    private val rotatingPrivateKey: ByteArray,
) : ApiRequest<AddPaymentStatus, AddProPaymentResponse> {
    override val endpoint: String
        get() = "add_pro_payment"

    override fun buildJsonBody(): String {
        return BackendRequests.buildAddProPaymentRequestJson(
            version = 0,
            masterPrivateKey = masterPrivateKey,
            rotatingPrivateKey = rotatingPrivateKey,
            paymentProvider = BackendRequests.PAYMENT_PROVIDER_GOOGLE_PLAY,
            paymentId = googlePaymentToken,
            orderId = googleOrderId,
        )
    }

    override fun convertStatus(status: Int): AddPaymentStatus {
        return AddPaymentStatus.entries.firstOrNull { it.apiValue == status }
            ?: AddPaymentStatus.GenericError
    }

    override val responseDeserializer: DeserializationStrategy<AddProPaymentResponse>
        get() = ProProofSerializer()

}

enum class AddPaymentStatus(val apiValue: Int) {
    Success(0),
    GenericError(1),
    AlreadyRedeemed(2),
    UnknownPayment(3),
}

typealias AddProPaymentResponse = ProProof