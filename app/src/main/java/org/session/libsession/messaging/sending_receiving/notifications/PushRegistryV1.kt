package org.session.libsession.messaging.sending_receiving.notifications

import android.annotation.SuppressLint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import nl.komponents.kovenant.Promise
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.network.onion.Version
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.emptyPromise
import org.session.libsignal.utilities.retryWithUniformInterval

@SuppressLint("StaticFieldLeak")
object PushRegistryV1 {
    val context = MessagingModuleConfiguration.shared.context
    private const val MAX_RETRY_COUNT = 4

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
        val parameters = mapOf("closedGroupPublicKey" to closedGroupPublicKey, "pubKey" to publicKey)
        val url = "${server.url}/$operation"
        val body = JsonUtil.toJson(parameters).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        try {
            retryWithUniformInterval(MAX_RETRY_COUNT) {
                MessagingModuleConfiguration.shared.serverClient.send(
                    request = request,
                    serverBaseUrl = server.url,
                    x25519PublicKey = server.publicKey,
                    version = Version.V2
                )

                // todo ONION the old code was checking the status code on success and if it is null or 0 it would log it as a fail
                // the new structure however throws all non 200.299 status as an OnionError
            }
        } catch (e: Exception) {
            Log.w("PushRegistryV1", "Failed to perform group operation ($operation): $e")
        }
    }
}
