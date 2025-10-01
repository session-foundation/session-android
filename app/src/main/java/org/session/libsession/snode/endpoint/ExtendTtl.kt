package org.session.libsession.snode.endpoint

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import org.session.libsession.utilities.serializable.InstantAsMillisSerializer
import java.time.Instant

object ExtendTtl : SimpleJsonEndpoint<ExtendTtl.Request, ExtendTtl.Response>() {
    override val requestSerializer: SerializationStrategy<Request>
        get() = Request.serializer()

    override val responseDeserializer: DeserializationStrategy<Response>
        get() = Response.serializer()

    override val methodName: String
        get() = "expire"

    override fun batchKey(request: Request): String? = Endpoint.BATCH_KEY_ALWAYS

    @Serializable
    class Request(
        @SerialName("expiry")
        @Serializable(with = InstantAsMillisSerializer::class)
        val newExpiry: Instant,

        @SerialName("messages")
        val messageHashes: List<String>,

        val extend: Boolean = true
    ) : WithSigningData {

        override fun getSigningData(timestampMs: Long): ByteArray {
            return buildString {
                append("expireextend")
                append(newExpiry.toEpochMilli())
                messageHashes.forEach(this::append)
            }.toByteArray()
        }
    }

    @Serializable
    class Response
}