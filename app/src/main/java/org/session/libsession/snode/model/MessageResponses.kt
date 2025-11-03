package org.session.libsession.snode.model

import android.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.session.libsession.utilities.serializable.InstantAsMillisSerializer
import java.time.Instant

@Serializable
data class StoreMessageResponse(
    val hash: String,
    @Serializable(InstantAsMillisSerializer::class)
    @SerialName("t") val timestamp: Instant,
)

@Serializable
data class RetrieveMessageResponse(
    val messages: List<Message>,
) {
    @Serializable
    data class Message(
        val hash: String,
        @Serializable(InstantAsMillisSerializer::class)
        @SerialName("t")
        val timestamp: Instant,
        @SerialName("data")
        val dataB64: String? = null,
    ) {
        val data: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
            Base64.decode(dataB64, Base64.DEFAULT)
        }
    }
}