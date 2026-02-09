package org.session.libsession.utilities.serializable

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * Serializes and deserializes [java.time.Instant] as a double representing seconds since epoch in UTC.
 */
class InstantAsSecondDoubleSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.session.InstantDouble", PrimitiveKind.DOUBLE)

    override fun serialize(
        encoder: Encoder,
        value: Instant
    ) {
        encoder.encodeDouble(value.toEpochMilli() / 1000.0)
    }

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.ofEpochMilli((decoder.decodeDouble() * 1000.0).toLong())
    }
}