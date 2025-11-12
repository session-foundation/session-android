package org.thoughtcrime.securesms.pro.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.DeserializationStrategy
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.ProProof
import org.session.libsession.snode.SnodeClock

class GetProProofRequest @AssistedInject constructor(
    @Assisted("master") private val masterPrivateKey: ByteArray,
    @Assisted private val rotatingPrivateKey: ByteArray,
    private val snodeClock: SnodeClock,
) : ApiRequest<GetProProofStatus, GetProProofResponse> {
    override val endpoint: String
        get() = "get_pro_proof"

    override fun buildJsonBody(): String {
        val now = snodeClock.currentTime()
        return BackendRequests.buildGetProProofRequestJson(
            version = 0,
            masterPrivateKey = masterPrivateKey,
            rotatingPrivateKey = rotatingPrivateKey,
            nowMs = now.toEpochMilli(),
        )
    }

    override val responseDeserializer: DeserializationStrategy<GetProProofResponse>
        get() = ProProofSerializer()

    override fun convertStatus(status: Int): GetProProofStatus = status

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("master") masterPrivateKey: ByteArray,
            rotatingPrivateKey: ByteArray,
        ): GetProProofRequest
    }
}

typealias GetProProofStatus = Int
typealias GetProProofResponse = ProProof