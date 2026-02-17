package org.thoughtcrime.securesms.emoji

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.toHexString

/**
 * Serializes a String as a hex UTF-16 code point.
 */
class StringAsHexUtf16Serializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("StringAsHexUtf16", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value.toByteArray(Charsets.UTF_16).toHexString())
    }

    override fun deserialize(decoder: Decoder): String {
        val utf16Encoded = Hex.fromStringCondensed(decoder.decodeString())
        return String(utf16Encoded, Charsets.UTF_16)
    }
}
