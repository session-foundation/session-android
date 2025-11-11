package org.session.libsession.messaging.sending_receiving.notifications

import android.annotation.SuppressLint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import nl.komponents.kovenant.Promise
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.snode.OnionResponse
import org.session.libsession.snode.Version
import org.session.libsession.snode.utilities.asyncPromise
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.JsonUtil
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
    ) = if (isPushEnabled) {
        performGroupOperation("subscribe_closed_group", closedGroupSessionId, publicKey)
    } else emptyPromise()

    fun unsubscribeGroup(
        closedGroupPublicKey: String,
        isPushEnabled: Boolean = TextSecurePreferences.isPushEnabled(context),
        publicKey: String = MessagingModuleConfiguration.shared.storage.getUserPublicKey()!!
    ) = if (isPushEnabled) {
        performGroupOperation("unsubscribe_closed_group", closedGroupPublicKey, publicKey)
    } else emptyPromise()

    private fun performGroupOperation(
        operation: String,
        closedGroupPublicKey: String,
        publicKey: String
    ): Promise<*, Exception> = scope.asyncPromise {
        val parameters = mapOf("closedGroupPublicKey" to closedGroupPublicKey, "pubKey" to publicKey)
        val url = "${server.url}/$operation"
        val body = JsonUtil.toJson(parameters).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        retryWithUniformInterval(MAX_RETRY_COUNT) {
            sendOnionRequest(request)
                .await()
                .checkError()
        }
    }

    private fun OnionResponse.checkError() {
        check(code != null && code != 0) {
            "error: $message."
        }
    }

    private fun sendOnionRequest(request: Request): Promise<OnionResponse, Exception> = OnionRequestAPI.sendOnionRequest(
        request,
        server.url,
        server.publicKey,
        Version.V2
    )
}
