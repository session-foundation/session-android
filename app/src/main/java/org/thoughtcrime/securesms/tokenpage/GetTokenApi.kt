package org.thoughtcrime.securesms.tokenpage

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.session.libsession.network.ServerClientErrorManager
import org.session.libsession.network.SnodeClock
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.http.HttpResponse
import org.thoughtcrime.securesms.api.server.ServerApi
import org.thoughtcrime.securesms.auth.LoginStateRepository
import javax.inject.Inject

class GetTokenApi @Inject constructor(
    private val loginStateRepository: LoginStateRepository,
    private val clock: SnodeClock,
    private val json: Json,
    errorManager: ServerClientErrorManager
) : ServerApi<InfoResponse>(errorManager) {
    override fun buildRequest(
        baseUrl: String,
        x25519PubKeyHex: String
    ): HttpRequest {
        val userEd25519SecKey = loginStateRepository
            .requireLoggedInState()
            .accountEd25519KeyPair
            .secretKey
            .data

        val userBlindedKeys = BlindKeyAPI.blindVersionKeyPair(userEd25519SecKey)
        val timestampSeconds = clock.currentTimeSeconds()

        val signature = BlindKeyAPI.blindVersionSignRequest(
            ed25519SecretKey = userEd25519SecKey,
            timestamp = timestampSeconds,
            path = "/info",
            body = null,
            method = "GET"
        )

        return HttpRequest(
            method = "GET",
            url = "$baseUrl/info".toHttpUrl(),
            headers = mapOf(
                "X-FS-Pubkey" to "07" + userBlindedKeys.pubKey.data.toHexString(),
                "X-FS-Timestamp" to timestampSeconds.toString(),
                "X-FS-Signature" to Base64.encodeBytes(signature),
            ),
            body = null,
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): InfoResponse {
        return response.body.asInputStream().use(json::decodeFromStream)
    }
}