package org.session.libsession.utilities.recipients

import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.UserPic

/**
 * Represents a remote file that can be downloaded.
 */
sealed interface RemoteFile {

    fun toUserPic(): UserPic?

    data class Encrypted(val url: String, val key: Bytes) : RemoteFile {
        override fun toUserPic(): UserPic {
            return UserPic(url, key)
        }

        override fun toString(): String {
            return "Encrypted($url)"
        }
    }

    data class Community(
        val communityServerBaseUrl: String,
        val roomId: String,
        val fileId: String
    ) : RemoteFile {
        override fun toUserPic(): UserPic? {
            return null
        }
    }

    companion object {
        fun UserPic.toRemoteFile(): Encrypted? {
            return when {
                url.isBlank() -> null
                else -> Encrypted(
                    url = url,
                    key = key
                )
            }
        }

        fun from(url: String, bytes: ByteArray?): RemoteFile? {
            return if (url.isNotBlank() && bytes != null && bytes.isNotEmpty()) {
                Encrypted(url, Bytes(bytes))
            } else {
                null
            }
        }
    }
}