package org.thoughtcrime.securesms.crypto

import kotlinx.serialization.Serializable
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.utilities.serializable.BytesAsCompactB64Serializer

@Serializable
class SealedData(
    @Serializable(with = BytesAsCompactB64Serializer::class)
    val iv: ByteArray,

    @Serializable(with = BytesAsCompactB64Serializer::class)
    val data: ByteArray,
) {
    @Deprecated("Use dependency injected json instead")
    fun serialize(): String {
        return MessagingModuleConfiguration.shared.json.encodeToString(this)
    }

    companion object {
        @JvmStatic
        @Deprecated("Use dependency injected json instead")
        fun fromString(serialized: String): SealedData {
            return MessagingModuleConfiguration.shared.json.decodeFromString(serialized)
        }

    }

}