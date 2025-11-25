package org.session.libsession.snode.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable

data class BatchResponse(val results: List<Item>, ) {
    @Serializable
    data class Item(
        val code: Int,
        val body: JsonElement,
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
