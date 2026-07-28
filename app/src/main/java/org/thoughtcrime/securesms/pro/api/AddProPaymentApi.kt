package org.thoughtcrime.securesms.pro.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.ProProofResponse
import network.loki.messenger.libsession_util.pro.ProRequest

class AddProPaymentApi @AssistedInject constructor(
    @Assisted("token") private val googlePaymentToken: String,
    @Assisted private val googleOrderId: String,
    @Assisted("master") private val masterPrivateKey: ByteArray,
    @Assisted private val rotatingPrivateKey: ByteArray,
    deps: ProApiDependencies
) : ProApi<ProProofResponse>(deps) {
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
