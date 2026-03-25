package org.session.libsession.utilities

import network.loki.messenger.libsession_util.util.BaseCommunityInfo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.session.libsignal.utilities.toHexString
import org.session.libsession.messaging.open_groups.migrateLegacyServerUrl

object CommunityUrlParser {

    private const val publicKeyQuery = "public_key"
    private const val hexPubkeyLength = 64

    sealed class Error(message: String) : IllegalArgumentException(message) {
        data object InvalidUrl : Error("Invalid community URL.")
        data object InvalidPublicKey : Error("Invalid public key provided.")
    }

    data class CommunityUrlInfo(
        val baseUrl: String,
        val room: String,
        val pubKeyHex: String,
    )

    fun trimQueryParameter(url: String): String = url.substringBefore("?$publicKeyQuery")

    fun parse(url: String): CommunityUrlInfo =
        try {
            val (baseUrl, room, publicKey) = requireNotNull(BaseCommunityInfo.parseFullUrl(url)) {
                "Invalid community URL"
            }
            CommunityUrlInfo(
                baseUrl = baseUrl.migrateLegacyServerUrl(),
                room = room,
                pubKeyHex = publicKey.toHexString(),
            )
        } catch (_: Exception) {
            throw classifyError(url)
        }

    private fun classifyError(url: String): Error {
        val parsedUrl = url.toHttpUrlOrNull() ?: return Error.InvalidUrl
        val pathSegments = parsedUrl.pathSegments.filter { it.isNotEmpty() }
        val room = when {
            pathSegments.firstOrNull() == "r" -> pathSegments.getOrNull(1)
            else -> pathSegments.firstOrNull()
        } ?: return Error.InvalidUrl

        val encodedPubkey = parsedUrl.queryParameter(publicKeyQuery) ?: return Error.InvalidPublicKey
        if (encodedPubkey.length != hexPubkeyLength) return Error.InvalidPublicKey

        return Error.InvalidUrl
    }
}
