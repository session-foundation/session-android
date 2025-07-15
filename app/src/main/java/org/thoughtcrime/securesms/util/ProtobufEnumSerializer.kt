package org.thoughtcrime.securesms.util

import com.google.protobuf.ProtocolMessageEnum
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A base serializer for Protobuf enums that implements the [KSerializer] interface.
 * It provides a way to serialize and deserialize Protobuf enum values using their numeric representation.
 *
 * @param T The type of the Protobuf enum that extends [ProtocolMessageEnum].
 */
abstract class ProtobufEnumSerializer<T : ProtocolMessageEnum> : KSerializer<T> {
    abstract fun fromNumber(number: Int): T

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        serialName = javaClass.simpleName,
        kind = PrimitiveKind.INT
    )

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeInt(value.number)
    }

    override fun deserialize(decoder: Decoder): T {
        return fromNumber(decoder.decodeInt())
    }
}