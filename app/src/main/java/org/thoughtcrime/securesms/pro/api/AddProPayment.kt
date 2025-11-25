package org.thoughtcrime.securesms.pro.api

import kotlinx.serialization.DeserializationStrategy
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.ProProof
import org.session.libsignal.utilities.Log

class AddProPaymentRequest(
    private val googlePaymentToken: String,
    private val googleOrderId: String,
    private val masterPrivateKey: ByteArray,
    private val rotatingPrivateKey: ByteArray,
) : ApiRequest<AddPaymentErrorStatus, ProProof> {
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

    override fun convertErrorStatus(status: Int): AddPaymentErrorStatus {
        Log.w("", "AddProPayment: convertErrorStatus: $status")
        return AddPaymentErrorStatus.entries.firstOrNull { it.apiValue == status }
            ?: AddPaymentErrorStatus.GenericError
    }

    override val responseDeserializer: DeserializationStrategy<ProProof>
        get() = ProProof.serializer()

}

enum class AddPaymentErrorStatus(val apiValue: Int) {
    GenericError(1),
    AlreadyRedeemed(100),
    UnknownPayment(101),
}
