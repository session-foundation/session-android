package org.session.libsession.snode.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class OnionSnodeRequest(
    val method: String,
    val params: JsonElement,
)