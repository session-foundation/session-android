package org.session.libsession.utilities.serializable

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.toHexString

class ByteArrayAsHexSerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(javaClass.name, PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ByteArray
    ) {
        encoder.encodeString(value.toHexString())
    }

    override fun deserialize(decoder: Decoder): ByteArray {
        return decoder.decodeString().let(Hex::fromStringCondensed)
    }
}