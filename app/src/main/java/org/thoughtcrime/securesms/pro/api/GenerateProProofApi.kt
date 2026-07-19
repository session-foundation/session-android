package org.thoughtcrime.securesms.pro.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.ProProofResponse
import network.loki.messenger.libsession_util.pro.ProRequest
import org.session.libsession.network.SnodeClock

class GenerateProProofApi @AssistedInject constructor(
    @Assisted("master") private val masterPrivateKey: ByteArray,
    @Assisted private val rotatingPrivateKey: ByteArray,
    private val snodeClock: SnodeClock,
    deps: ProApiDependencies,
) : ProApi<GetProProofStatus, ProProofResponse>(deps) {

    override fun buildProRequest(): ProRequest =
        BackendRequests.buildGenerateProProofRequest(
            masterPrivateKey = masterPrivateKey,
            rotatingPrivateKey = rotatingPrivateKey,
            nowSeconds = snodeClock.currentTime().epochSecond,
        )

    override fun parseResponse(json: String): ProProofResponse =
        BackendRequests.parseProProofResponse(json)

    override fun convertErrorStatus(status: Int): GetProProofStatus = status

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("master") masterPrivateKey: ByteArray,
            rotatingPrivateKey: ByteArray,
        ): GenerateProProofApi
    }
}

typealias GetProProofStatus = Int