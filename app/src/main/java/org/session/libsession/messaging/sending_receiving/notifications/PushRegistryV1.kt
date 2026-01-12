package org.session.libsession.messaging.sending_receiving.notifications

import android.annotation.SuppressLint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.network.onion.Version
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log

@SuppressLint("StaticFieldLeak")
object PushRegistryV1 {
    val context = MessagingModuleConfiguration.shared.context

    private val server = Server.LEGACY

    @Suppress("OPT_IN_USAGE")
    private val scope: CoroutineScope = GlobalScope

    // Legacy Closed Groups

    fun subscribeGroup(
        closedGroupSessionId: String,
        isPushEnabled: Boolean = TextSecurePreferences.isPushEnabled(context),
        publicKey: String = MessagingModuleConfiguration.shared.storage.getUserPublicKey()!!
    ) {
        if (!isPushEnabled) return
        scope.launch {
            performGroupOperation("subscribe_closed_group", closedGroupSessionId, publicKey)
        }
    }

    fun unsubscribeGroup(
        closedGroupPublicKey: String,
        isPushEnabled: Boolean = TextSecurePreferences.isPushEnabled(context),
        publicKey: String = MessagingModuleConfiguration.shared.storage.getUserPublicKey()!!
    ) {
        if (!isPushEnabled) return
        scope.launch {
            performGroupOperation("unsubscribe_closed_group", closedGroupPublicKey, publicKey)
        }
    }

    private suspend fun performGroupOperation(
        operation: String,
        closedGroupPublicKey: String,
        publicKey: String
    ) {
        val url = "${server.url}/$operation"

        try {
            MessagingModuleConfiguration.shared.serverClient.send(
                operationName = operation,
                requestFactory = {
                    val parameters = mapOf(
                        "closedGroupPublicKey" to closedGroupPublicKey,
                        "pubKey" to publicKey
                    )

                    val body = JsonUtil.toJson(parameters)
                        .toRequestBody("application/json".toMediaType())

                    Request.Builder()
                        .url(url)
                        .post(body)
                        .build()
                },
                serverBaseUrl = server.url,
                x25519PublicKey = server.publicKey,
                version = Version.V2
            )
        } catch (e: Exception) {
            Log.w("PushRegistryV1", "Failed to perform group operation ($operation): $e")
        }
    }
}
