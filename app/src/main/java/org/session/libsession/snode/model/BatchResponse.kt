package org.session.libsession.snode.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

data class BatchResponse @JsonCreator constructor(
    @param:JsonProperty("results") val results: List<Item>,
) {
    data class Item @JsonCreator constructor(
        @param:JsonProperty("code") val code: Int,
        @param:JsonProperty("body") val body: JsonNode,
    ) {
        val isSuccessful: Boolean
            get() = code in 200..299

        val isServerError: Boolean
            get() = code in 500..599

        val isSnodeNoLongerPartOfSwarm: Boolean
            get() = code == 421
    }

    data class Error(val item: Item)
        : RuntimeException("Batch request failed with code ${item.code}") {
        init {
            require(!item.isSuccessful) {
                "This response item does not represent an error state"
            }
        }
    }
}
