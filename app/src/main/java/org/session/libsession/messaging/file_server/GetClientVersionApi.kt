package org.session.libsession.messaging.file_server

import android.util.Base64
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.thoughtcrime.securesms.api.server.ServerApiErrorManager
import org.session.libsession.network.SnodeClock
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.api.ApiExecutorContext
import org.thoughtcrime.securesms.api.http.HttpRequest
import org.thoughtcrime.securesms.api.http.HttpResponse
import org.thoughtcrime.securesms.api.server.ServerApi
import org.thoughtcrime.securesms.auth.LoginStateRepository
import javax.inject.Inject

/**
 * Returns the current version of session
 * This is effectively proxying (and caching) the response from the github release
 * page.
 *
 * Note that the value is cached and can be up to 30 minutes out of date normally, and up to 24
 * hours out of date if we cannot reach the Github API for some reason.
 *
 * https://github.com/session-foundation/session-file-server/blob/dev/doc/api.yaml#L119
 */
class GetClientVersionApi @Inject constructor(
    errorManager: ServerApiErrorManager,
    private val loginStateRepository: LoginStateRepository,
    private val snodeClock: SnodeClock,
    private val json: Json,
) : ServerApi<VersionData>(errorManager) {
    override fun buildRequest(
        baseUrl: String,
        x25519PubKeyHex: String
    ): HttpRequest {
        val secretKey = loginStateRepository
            .requireLoggedInState()
            .accountEd25519KeyPair
            .secretKey
            .data

        val blindedKeys = BlindKeyAPI.blindVersionKeyPair(secretKey)

        // The hex encoded version-blinded public key with a 07 prefix
        val blindedPkHex = "07" + blindedKeys.pubKey.data.toHexString()

        val timestamp = snodeClock.currentTimeSeconds()
        val signature = BlindKeyAPI.blindVersionSign(secretKey, timestamp)

        return HttpRequest(
            url = "$baseUrl/session_version?platform=android".toHttpUrl(),
            method = "GET",
            headers = mapOf(
                "X-FS-Pubkey" to blindedPkHex,
                "X-FS-Timestamp" to timestamp.toString(),
                "X-FS-Signature" to Base64.encodeToString(signature, Base64.NO_WRAP),
            ),
            body = null
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun handleSuccessResponse(
        executorContext: ApiExecutorContext,
        baseUrl: String,
        response: HttpResponse
    ): VersionData {
        return response.body.asInputStream()
            .use(json::decodeFromStream)
    }
}