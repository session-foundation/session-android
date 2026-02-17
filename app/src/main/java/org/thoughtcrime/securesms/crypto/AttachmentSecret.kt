package org.thoughtcrime.securesms.crypto

import kotlinx.serialization.Serializable
import org.session.libsession.utilities.serializable.BytesAsCompactB64Serializer

@Serializable
class AttachmentSecret(
    @Serializable(with = BytesAsCompactB64Serializer::class)
    val classicMacKey: ByteArray? = null,

    @Serializable(with = BytesAsCompactB64Serializer::class)
    val classicCipherKey: ByteArray? = null,

    @Serializable(with = BytesAsCompactB64Serializer::class)
    val modernKey: ByteArray,
)