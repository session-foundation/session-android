package org.session.libsignal.database

import org.thoughtcrime.securesms.database.model.MessageId

interface LokiMessageDatabaseProtocol {

    fun setServerID(messageID: MessageId, serverID: Long)
}
