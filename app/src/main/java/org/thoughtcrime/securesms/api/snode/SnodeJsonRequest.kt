package org.thoughtcrime.securesms.api.snode

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A generic JSON-RPC request format to be sent to a service node.
 */
@Serializable
class SnodeJsonRequest(
    val method: String,
    val params: JsonElement,
)