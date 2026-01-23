package org.thoughtcrime.securesms.api.snode

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.session.libsession.network.SnodeClientErrorManager
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.snode.model.StoreMessageResponse

class StoreMessageApi @AssistedInject constructor(
    @Assisted private val message: SnodeMessage,
    @Assisted private val auth: SwarmAuth?,
    @Assisted private val namespace: Int,
    errorManager: SnodeClientErrorManager,
    private val snodeClock: SnodeClock,
    private val json: Json,
) : AbstractSnodeApi<StoreMessageResponse>(
    snodeClientErrorManager = errorManager
) {
    override fun deserializeSuccessResponse(body: JsonElement): StoreMessageResponse {
        return json.decodeFromJsonElement(StoreMessageResponse.serializer(), body)
    }

    override val methodName: String
        get() = "store"

    override fun buildParams(): JsonElement {
        return if (auth != null) {
            check(auth.accountId.hexString == message.recipient) {
                "Message sent to ${message.recipient} but authenticated with ${auth.accountId.hexString}"
            }

            val timestamp = snodeClock.currentTimeMillis()

            buildAuthenticatedParameters(
                auth = auth,
                namespace = namespace,
                verificationData = { ns, t -> "$methodName$ns$t" },
                timestamp = timestamp
            ) {
                put("sig_timestamp", JsonPrimitive(timestamp))
                putAll(message.toJSON().mapValues { JsonPrimitive(it.value) })
            }
        } else {
            JsonObject(
                buildMap {
                    putAll(message.toJSON().mapValues { JsonPrimitive(it.value) })
                    if (namespace != 0) put("namespace", JsonPrimitive(namespace))
                }
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(message: SnodeMessage, auth: SwarmAuth?, namespace: Int): StoreMessageApi
    }
}