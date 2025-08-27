package org.thoughtcrime.securesms.coil

import coil3.key.Keyer
import coil3.request.Options
import org.session.libsession.utilities.recipients.RemoteFile
import org.session.libsignal.utilities.toHexString
import javax.inject.Inject

class RemoteFileKeyer @Inject constructor() : Keyer<RemoteFile> {
    override fun key(
        data: RemoteFile,
        options: Options
    ): String {
        return when (data) {
            is RemoteFile.Encrypted -> "${data.url}-${data.key.data.toHexString()}"
            is RemoteFile.Community -> "${data.communityServerBaseUrl}-${data.roomId}-${data.fileId}"
        }
    }
}