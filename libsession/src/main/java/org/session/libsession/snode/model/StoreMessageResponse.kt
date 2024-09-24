package org.session.libsession.snode.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class StoreMessageResponse @JsonCreator constructor(
    @JsonProperty("hash") val hash: String,
    @JsonProperty("t") val timestamp: Long,
)
