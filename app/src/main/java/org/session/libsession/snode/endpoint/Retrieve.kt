package org.session.libsession.snode.endpoint

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import org.session.libsession.utilities.serializable.InstantAsMillisSerializer
import java.time.Instant

object Retrieve : SimpleJsonEndpoint<Retrieve.Request, Retrieve.Response>() {
    override val requestSerializer: SerializationStrategy<Request>
        get() = Request.serializer()
    override val responseDeserializer: DeserializationStrategy<Response>
        get() = Response.serializer()
    override val methodName: String
        get() = "retrieve"

    override fun batchKey(request: Request): String? = Endpoint.BATCH_KEY_ALWAYS

    @Serializable
    class Request(
        val namespace: Int?,

        @SerialName("last_hash")
        val lastHash: String,

        @SerialName("max_size")
        val maxSize: Int?,
    ) : WithSigningData {
        override fun getSigningData(timestampMs: Long): ByteArray {
            return if (namespace == null || namespace == 0) {
                "retrieve$timestampMs"
            } else {
                "retrieve$namespace$timestampMs"
            }.toByteArray()
        }
    }

    @Serializable
    class Response(val messages: List<Message>)

    @Serializable
    class Message(
        val data: String,
        val hash: String,
        @Serializable(with = InstantAsMillisSerializer::class)
        val timestamp: Instant,
        @Serializable(with = InstantAsMillisSerializer::class)
        val expiration: Instant
    )
}