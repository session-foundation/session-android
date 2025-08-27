package org.session.libsession.utilities

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.reflect.KClass
import kotlin.reflect.cast

/**
 * An abstract serializer for [Address] types.
 *
 * Normally, an [Address] is easy to serialize: it's built upon a string representation to start
 * with so serialization is straightforward. However, the same cannot be said for its subclasses:
 * if you want to explicitly serialize an [Address.Group], [Address.Standard], etc, you need
 * to cast the [Address] to the specific type, hence a custom serializer is needed for each
 * subclass.
 */
abstract class AbstractAddressSerializer<A : Address>(private val clazz: KClass<A>) : KSerializer<A> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "${clazz.simpleName}Serializer",
        PrimitiveKind.STRING
    )

    override fun serialize(
        encoder: Encoder,
        value: A
    ) {
        encoder.encodeString(value.address)
    }

    override fun deserialize(decoder: Decoder): A {
        return clazz.cast(Address.fromSerialized(decoder.decodeString()))
    }
}

object AddressSerializer : AbstractAddressSerializer<Address>(Address::class)
object GroupAddressSerializer : AbstractAddressSerializer<Address.Group>(Address.Group::class)
object StandardAddressSerializer : AbstractAddressSerializer<Address.Standard>(Address.Standard::class)
object ConversableAddressSerializer : AbstractAddressSerializer<Address.Conversable>(Address.Conversable::class)