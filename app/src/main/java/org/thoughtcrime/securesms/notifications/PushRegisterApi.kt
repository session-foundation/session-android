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
import org.session.libsession.messaging.sending_receiving.notifications.SubscriptionRequest
import org.session.libsession.messaging.sending_receiving.notifications.SubscriptionResponse
import org.session.libsession.network.ServerClientErrorManager
import org.session.libsession.network.SnodeClock
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.utilities.Device
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.api.server.JsonServerApi
import org.thoughtcrime.securesms.auth.LoginStateRepository

class PushRegisterApi @AssistedInject constructor(
    @Assisted private val token: String,
    @Assisted private val swarmAuth: SwarmAuth,
    @Assisted private val namespaces: List<Int>,
    private val clock: SnodeClock,
    private val device: Device,
    private val loginStateRepository: LoginStateRepository,
    json: Json,
    errorManager: ServerClientErrorManager
) : JsonServerApi<SubscriptionResponse>(json, errorManager) {
    override val httpMethod: String get() = "POST"
    override val httpEndpoint: String get() = "subscribe"
    override val responseSerializer: DeserializationStrategy<SubscriptionResponse>
        get() = SubscriptionResponse.serializer()

    override fun buildJsonPayload(): JsonElement {
        val timestamp = clock.currentTimeSeconds()
        val publicKey = swarmAuth.accountId.hexString
        val sortedNamespace = namespaces.sorted()
        val signed = JsonObject(swarmAuth.sign(
            "MONITOR${publicKey}${timestamp}1${sortedNamespace.joinToString(separator = ",")}".encodeToByteArray()
        ).mapValues { JsonPrimitive(it.value) })

        return JsonObject(SubscriptionRequest(
            pubkey = publicKey,
            session_ed25519 = swarmAuth.ed25519PublicKeyHex,
            namespaces = sortedNamespace,
            data = true, // only permit data subscription for now (?)
            service = device.service,
            sig_ts = timestamp,
            service_info = mapOf("token" to token),
            enc_key = loginStateRepository.requireLoggedInState().notificationKey.data.toHexString(),
        ).let(Json::encodeToJsonElement).jsonObject + signed)
    }

    @AssistedFactory
    interface Factory {
        fun create(
            token: String,
            swarmAuth: SwarmAuth,
            namespaces: List<Int>
        ): PushRegisterApi
    }
}