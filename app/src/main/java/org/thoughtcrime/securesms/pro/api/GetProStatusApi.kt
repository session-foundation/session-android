package org.thoughtcrime.securesms.pro.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.GetProStatusResponse
import network.loki.messenger.libsession_util.pro.ProRequest
import org.session.libsession.network.SnodeClock

class GetProStatusApi @AssistedInject constructor(
    private val snodeClock: SnodeClock,
    @Assisted private val masterPrivateKey: ByteArray,
    deps: ProApiDependencies,
) : ProApi<GetProStatusResponse>(deps) {
    override fun buildProRequest(): ProRequest =
        BackendRequests.buildGetProStatusRequest(
            masterPrivateKey = masterPrivateKey,
            nowSeconds = snodeClock.currentTimeMillis() / 1000,
        )

    override fun parseResponse(json: String): GetProStatusResponse =
        BackendRequests.parseProStatusResponse(json)

    @AssistedFactory
    interface Factory {
        fun create(masterPrivateKey: ByteArray): GetProStatusApi
    }
}
