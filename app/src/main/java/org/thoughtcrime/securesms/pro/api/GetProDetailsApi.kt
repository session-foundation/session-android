package org.thoughtcrime.securesms.pro.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.GetProDetailsResponse
import network.loki.messenger.libsession_util.pro.ProRequest
import org.session.libsession.network.SnodeClock

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
