package org.thoughtcrime.securesms.pro.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import network.loki.messenger.libsession_util.pro.ProProof
import org.session.libsession.utilities.serializable.ByteArrayAsHexSerializer

class ProProofSerializer: KSerializer<ProProof> {
    private val byteArrayAsHexSerializer = ByteArrayAsHexSerializer()

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(ProProof::class.java.name) {
        element<Long>("expiry_unix_ts_ms")
        element("gen_index_hash", byteArrayAsHexSerializer.descriptor)
        element("rotating_pkey", byteArrayAsHexSerializer.descriptor)
        element("sig", byteArrayAsHexSerializer.descriptor)
        element<Int>("version")
    }

    override fun serialize(
        encoder: Encoder,
        value: ProProof
    ) {
        encoder.encodeStructure(descriptor) {
            encodeLongElement(descriptor, 0, value.expiryMs)
            encodeSerializableElement(descriptor, 1, byteArrayAsHexSerializer, value.genIndexHash)
            encodeSerializableElement(descriptor, 2, byteArrayAsHexSerializer, value.rotatingPubKey)
            encodeSerializableElement(descriptor, 3, byteArrayAsHexSerializer, value.signature)
            encodeIntElement(descriptor, 4, value.version)
        }
    }

    override fun deserialize(decoder: Decoder): ProProof {
        return decoder.decodeStructure(descriptor) {
            var expiryMs: Long = 0
            var genIndexHash = ByteArray(0)
            var rotatingPubKey = ByteArray(0)
            var signature = ByteArray(0)
            var version = 0

            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> expiryMs = decodeLongElement(descriptor, 0)
                    1 -> genIndexHash = decodeSerializableElement(descriptor, 1, byteArrayAsHexSerializer)
                    2 -> rotatingPubKey = decodeSerializableElement(descriptor, 2, byteArrayAsHexSerializer)
                    3 -> signature = decodeSerializableElement(descriptor, 3, byteArrayAsHexSerializer)
                    4 -> version = decodeIntElement(descriptor, 4)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> throw IllegalStateException("Unexpected index: $index")
                }
            }

            ProProof(
                expiryMs = expiryMs,
                genIndexHash = genIndexHash,
                rotatingPubKey = rotatingPubKey,
                signature = signature,
                version = version
            )
        }
    }
}