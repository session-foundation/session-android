package org.session.libsession.snode.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class BatchResponse @JsonCreator constructor(
    @param:JsonProperty("results") val results: List<Item>,
) {
    data class Item @JsonCreator constructor(
        @param:JsonProperty("code") val code: Int,
        @param:JsonProperty("body") val body: Map<String, Any?>?,
    )
}
