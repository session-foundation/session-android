package org.thoughtcrime.securesms.tokenpage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.api.server.ServerApiRequest
import org.thoughtcrime.securesms.api.server.execute
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

interface TokenRepository {
    suspend fun getInfoResponse(): InfoResponse?
}

@Singleton
class TokenRepositoryImpl @Inject constructor(
    @param:ApplicationContext val context: Context,
    private val serverApiExecutor: ServerApiExecutor,
    private val getTokenApi: Provider<GetTokenApi>,
): TokenRepository {
    // Method to access the /info endpoint and retrieve a InfoResponse via onion-routing.
    override suspend fun getInfoResponse(): InfoResponse {
        return serverApiExecutor.execute(ServerApiRequest(
            serverBaseUrl = TOKEN_SERVER_URL,
            serverX25519PubKeyHex = TOKEN_SERVER_PUBLIC_KEY,
            api = getTokenApi.get(),
        ))
    }

    companion object {
        private const val TOKEN_SERVER_URL = "http://networkv1.getsession.org"
        private const val TOKEN_SERVER_PUBLIC_KEY = "cbf461a4431dc9174dceef4421680d743a2a0e1a3131fc794240bcb0bc3dd449"
    }

}