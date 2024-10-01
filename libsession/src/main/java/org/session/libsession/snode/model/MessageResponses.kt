package org.session.libsession.snode.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class StoreMessageResponse @JsonCreator constructor(
    @JsonProperty("hash") val hash: String,
    @JsonProperty("t") val timestamp: Long,
)

class RetrieveMessageResponse @JsonCreator constructor(
    @JsonProperty("messages") val messages: List<Message>,
) {
    class Message @JsonCreator constructor(
        @JsonProperty("hash") val hash: String,
        @JsonProperty("t") val timestamp: Long,
        // Jackson is able to deserialize byte arrays from base64 strings
        @JsonProperty("data") val data: ByteArray,
    )
}