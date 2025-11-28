package org.session.libsession.utilities.serializable

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import network.loki.messenger.libsession_util.util.Bytes
import org.session.libsignal.utilities.Base64

class BytesAsBase64Serializer : KSerializer<Bytes> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        serialName = Bytes::javaClass.name,
        kind = PrimitiveKind.STRING
    )

    override fun serialize(
        encoder: Encoder,
        value: Bytes
    ) {
        encoder.encodeString(Base64.encodeBytes(value.data))
    }

    override fun deserialize(decoder: Decoder): Bytes {
        return Bytes(Base64.decode(decoder.decodeString()))
    }
}
