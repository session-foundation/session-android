package org.session.libsession.utilities.serializable

import android.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class BytesAsCompactB64Serializer : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("BytesAsCompactB64Serializer", PrimitiveKind.STRING)

    private val base64Flags = Base64.NO_WRAP or Base64.NO_PADDING

    override fun serialize(
        encoder: Encoder,
        value: ByteArray
    ) {
        encoder.encodeString(Base64.encodeToString(value, base64Flags))
    }

    override fun deserialize(decoder: Decoder): ByteArray {
        return Base64.decode(decoder.decodeString(), base64Flags)
    }
}