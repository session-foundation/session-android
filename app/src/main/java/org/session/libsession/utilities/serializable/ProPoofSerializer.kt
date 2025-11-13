package org.session.libsession.utilities.serializable

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import network.loki.messenger.libsession_util.pro.ProProof

class ProPoofSerializer : KSerializer<ProProof> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        serialName = ProProof::javaClass.name,
        kind = PrimitiveKind.STRING
    )

    override fun serialize(
        encoder: Encoder,
        value: ProProof
    ) {
        encoder.encodeString(value.serialize())
    }

    override fun deserialize(decoder: Decoder): ProProof {
        return ProProof.deserialize(decoder.decodeString())
    }
}