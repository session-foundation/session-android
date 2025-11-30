package org.session.libsession.utilities.serializable

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import network.loki.messenger.libsession_util.util.KeyPair
import org.session.libsignal.utilities.Base64

class KeyPairAsArraySerializer : KSerializer<KeyPair> {
    private val stringArraySerializer: KSerializer<Array<String>> = ArraySerializer(String::class, String.serializer())
    override val descriptor = stringArraySerializer.descriptor

    override fun serialize(
        encoder: Encoder,
        value: KeyPair
    ) {
        stringArraySerializer.serialize(encoder, arrayOf(
            Base64.encodeBytes(value.pubKey.data),
            Base64.encodeBytes(value.secretKey.data)
        ))
    }

    override fun deserialize(decoder: Decoder): KeyPair {
        val (pubKeyBase64, secretKeyBase64) = stringArraySerializer.deserialize(decoder)
        return KeyPair(
            pubKey = Base64.decode(pubKeyBase64),
            secretKey = Base64.decode(secretKeyBase64)
        )
    }
}
