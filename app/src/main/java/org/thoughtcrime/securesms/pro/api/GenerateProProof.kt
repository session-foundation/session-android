package org.thoughtcrime.securesms.pro.api

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.DeserializationStrategy
import network.loki.messenger.libsession_util.pro.BackendRequests
import network.loki.messenger.libsession_util.pro.ProProof
import org.session.libsession.snode.SnodeClock

class GenerateProProofRequest @AssistedInject constructor(
    @Assisted("master") private val masterPrivateKey: ByteArray,
    @Assisted private val rotatingPrivateKey: ByteArray,
    private val snodeClock: SnodeClock,
) : ApiRequest<GetProProofStatus, ProProof> {
    override val endpoint: String
        get() = "generate_pro_proof"

    override fun buildJsonBody(): String {
        val now = snodeClock.currentTime()
        return BackendRequests.buildGenerateProProofRequestJson(
            version = 0,
            masterPrivateKey = masterPrivateKey,
            rotatingPrivateKey = rotatingPrivateKey,
            nowMs = now.toEpochMilli(),
        )
    }

    override val responseDeserializer: DeserializationStrategy<ProProof>
        get() = ProProof.serializer()

    override fun convertErrorStatus(status: Int): GetProProofStatus = status

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("master") masterPrivateKey: ByteArray,
            rotatingPrivateKey: ByteArray,
        ): GenerateProProofRequest
    }
}

typealias GetProProofStatus = Int