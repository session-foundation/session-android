package org.session.libsession.snode.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class BatchResponse(val results: List<Item>) {
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
}
