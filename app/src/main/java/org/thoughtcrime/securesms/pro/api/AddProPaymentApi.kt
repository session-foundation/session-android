package org.thoughtcrime.securesms.pro.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.ProProofResponse
import network.loki.messenger.libsession_util.pro.ProRequest
import org.session.libsignal.utilities.Log

class AddProPaymentApi @AssistedInject constructor(
    @Assisted("token") private val googlePaymentToken: String,
    @Assisted private val googleOrderId: String,
    @Assisted("master") private val masterPrivateKey: ByteArray,
    @Assisted private val rotatingPrivateKey: ByteArray,
    deps: ProApiDependencies
) : ProApi<AddPaymentErrorStatus, ProProofResponse>(deps) {
    override fun buildProRequest(): ProRequest =
        BackendRequests.buildAddProPaymentRequest(
            masterPrivateKey = masterPrivateKey,
            rotatingPrivateKey = rotatingPrivateKey,
            providerCode = BackendRequests.PAYMENT_PROVIDER_GOOGLE_PLAY,
            // Google composite payment_id = "<payment_token>|<order_id>" (backend splits on first '|').
            paymentId = "$googlePaymentToken|$googleOrderId",
        )

    override fun parseResponse(json: String): ProProofResponse =
        BackendRequests.parseAddPaymentResponse(json)

    override fun convertErrorStatus(status: Int): AddPaymentErrorStatus {
        Log.w("", "AddProPayment: convertErrorStatus: $status")
        return AddPaymentErrorStatus.entries.firstOrNull { it.apiValue == status }
            ?: AddPaymentErrorStatus.GenericError
    }

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
