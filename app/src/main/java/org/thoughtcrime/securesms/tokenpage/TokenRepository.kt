package org.thoughtcrime.securesms.tokenpage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.snode.utilities.await
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.toHexString
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

interface TokenRepository {
    suspend fun getInfoResponse(): InfoResponse?
}

@Singleton
class TokenRepositoryImpl @Inject constructor(
    @param:ApplicationContext val context: Context,
    private val storage: StorageProtocol,
    private val json: Json,
): TokenRepository {
    private val TAG = "TokenRepository"

    private val TOKEN_SERVER_URL = "http://networkv1.getsession.org"
    private val TOKEN_SERVER_INFO_ENDPOINT = "$TOKEN_SERVER_URL/info"
    private val SERVER_PUBLIC_KEY = "cbf461a4431dc9174dceef4421680d743a2a0e1a3131fc794240bcb0bc3dd449"
    
    private val secretKey by lazy {
        storage.getUserED25519KeyPair()?.secretKey?.data
            ?: throw (FileServerApi.Error.NoEd25519KeyPair)
    }

    private val userBlindedKeys by lazy {
        BlindKeyAPI.blindVersionKeyPair(secretKey)
    }

    private fun <T>defaultErrorHandling(e: Exception): T? {
        Log.e("TokenRepo", "Server error getting data: $e")
        return null
    }

    // Method to access the /info endpoint and retrieve a InfoResponse via onion-routing.
    override suspend fun getInfoResponse(): InfoResponse? {
        return sendOnionRequest<InfoResponse>(
                path = "info",
                url = TOKEN_SERVER_INFO_ENDPOINT
            )
    }

    private suspend inline fun <reified T>sendOnionRequest(
        path: String, url: String, body: ByteArray? = null,
        customCatch: (Exception) -> T? = { e -> defaultErrorHandling(e) }
    ): T? {
        val timestampSeconds = System.currentTimeMillis().milliseconds.inWholeSeconds
        val signature = BlindKeyAPI.blindVersionSignRequest(
            ed25519SecretKey = secretKey, // Important: Use the ED25519 secret key here and NOT the blinded secret key!
            timestamp = timestampSeconds,
            path = ("/$path"),
            body = body,
            method = if (body == null) "GET" else "POST"
        )

        val headersMap = mapOf(
            "X-FS-Pubkey" to "07" + userBlindedKeys.pubKey.data.toHexString(),
            "X-FS-Timestamp" to timestampSeconds.toString(),
            "X-FS-Signature" to Base64.encodeBytes(signature) // Careful: Do NOT add `android.util.Base64.NO_WRAP` to this - it breaks it.
        )

        var requestBuilder = Request.Builder()
        requestBuilder = if (body == null) {
            requestBuilder.get()
        } else {
            requestBuilder.post(body.toRequestBody())
        }
        val request = requestBuilder
            .url(url)
            .headers(headersMap.toHeaders())
            .build()

        var response: T? = null
        try {
            val rawResponse = OnionRequestAPI.sendOnionRequest(
                request = request,
                server = TOKEN_SERVER_URL, // Note: The `request` contains the actual endpoint we'll hit
                x25519PublicKey = SERVER_PUBLIC_KEY
            ).await()

            val resultJsonString = rawResponse.body?.decodeToString()
            if (resultJsonString == null) {
                Log.w(TAG, "${T::class.java} decoded to null")
            } else {
                response = json.decodeFromString(resultJsonString)
            }
        }
        catch (se: SerializationException) {
            Log.e(TAG, "Got a serialization exception attempting to decode ${T::class.java}", se)
        }
        catch (e: Exception) {
            val catchResponse = customCatch(e)
            Log.e(TAG, "Got an error: $catchResponse")
            return catchResponse
        }

        return response
    }
}