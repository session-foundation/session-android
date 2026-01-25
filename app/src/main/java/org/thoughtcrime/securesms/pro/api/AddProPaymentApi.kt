package org.thoughtcrime.securesms.pro.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.DeserializationStrategy
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.ProProof
import org.session.libsignal.utilities.Log

class AddProPaymentApi @AssistedInject constructor(
    @Assisted("token") private val googlePaymentToken: String,
    @Assisted private val googleOrderId: String,
    @Assisted("master") private val masterPrivateKey: ByteArray,
    @Assisted private val rotatingPrivateKey: ByteArray,
    deps: ProApiDependencies
) : ProApi<AddPaymentErrorStatus, ProProof>(deps) {
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

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("token") googlePaymentToken: String,
            googleOrderId: String,
            @Assisted("master") masterPrivateKey: ByteArray,
            rotatingPrivateKey: ByteArray,
        ): AddProPaymentApi
    }
}

enum class AddPaymentErrorStatus(val apiValue: Int) {
    GenericError(1),
    AlreadyRedeemed(100),
    UnknownPayment(101),
}
