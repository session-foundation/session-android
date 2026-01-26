package org.thoughtcrime.securesms.notifications

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.session.libsession.messaging.sending_receiving.notifications.UnsubscribeResponse
import org.session.libsession.messaging.sending_receiving.notifications.UnsubscriptionRequest
import org.thoughtcrime.securesms.api.server.ServerApiErrorManager
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.utilities.Device
import org.thoughtcrime.securesms.api.server.JsonServerApi

class PushUnregisterApi @AssistedInject constructor(
    @Assisted private val token: String,
    @Assisted private val swarmAuth: SwarmAuth,
    private val clock: SnodeClock,
    private val device: Device,
    json: Json,
    errorManager: ServerApiErrorManager
) : JsonServerApi<UnsubscribeResponse>(json, errorManager) {
    override val httpMethod: String get() = "POST"
    override val httpEndpoint: String get() = "unsubscribe"
    override val responseSerializer: DeserializationStrategy<UnsubscribeResponse>
        get() = UnsubscribeResponse.serializer()

    override fun buildJsonPayload(): JsonElement {
        val timestamp = clock.currentTimeSeconds()
        val publicKey = swarmAuth.accountId.hexString
        val signed = JsonObject(swarmAuth.sign(
            "UNSUBSCRIBE${publicKey}${timestamp}".encodeToByteArray()
        ).mapValues { JsonPrimitive(it.value) })

        return JsonObject(UnsubscriptionRequest(
            pubkey = publicKey,
            session_ed25519 = swarmAuth.ed25519PublicKeyHex,
            service = device.service,
            sig_ts = timestamp,
            service_info = mapOf("token" to token),
        ).let(Json::encodeToJsonElement).jsonObject + signed)
    }

    @AssistedFactory
    interface Factory {
        fun create(
            token: String,
            swarmAuth: SwarmAuth,
        ): PushUnregisterApi
    }
}