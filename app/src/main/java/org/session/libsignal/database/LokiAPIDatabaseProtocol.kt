package org.session.libsignal.database

import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.utilities.ForkInfo
import org.session.libsignal.utilities.Snode
import java.util.Date

interface LokiAPIDatabaseProtocol {

    fun getLastMessageHashValue(snode: Snode, publicKey: String, namespace: Int): String?

    /** Taken when a poll starts, before it reads any cursor, and passed to [setLastMessageHashValue]. */
    fun lastMessageHashEpoch(): LastMessageHashEpoch

    /**
     * Writes [newValue] as the cursor, unless [publicKey]'s cursors have been reset since [since].
     *
     * A reset asks for the swarm's history to be fetched again. A poll that was in flight when it happened
     * would otherwise finish afterwards and write its position back, undoing the reset, and the history
     * would never be fetched. Its messages are still handled; only the cursor write is dropped, so the next
     * poll starts from the beginning and dedupe absorbs what it fetches twice.
     *
     * @return whether the cursor was written.
     */
    fun setLastMessageHashValue(
        snode: Snode,
        publicKey: String,
        newValue: String,
        namespace: Int,
        since: LastMessageHashEpoch,
    ): Boolean
    fun clearLastMessageHashes(publicKey: String)
    fun clearLastMessageHashesByNamespaces(vararg namespaces: Int)
    fun clearAllLastMessageHashes()
    fun getAuthToken(server: String): String?
    fun setAuthToken(server: String, newValue: String?)
    fun getLastMessageServerID(room: String, server: String): Long?
    fun setLastMessageServerID(room: String, server: String, newValue: Long)
    fun getLastDeletionServerID(room: String, server: String): Long?
    fun setLastDeletionServerID(room: String, server: String, newValue: Long)
    fun getOpenGroupPublicKey(server: String): String?
    fun setOpenGroupPublicKey(server: String, newValue: String)
    fun getClosedGroupEncryptionKeyPairs(groupPublicKey: String): List<ECKeyPair>
    fun getLatestClosedGroupEncryptionKeyPair(groupPublicKey: String): ECKeyPair?
    fun getForkInfo(): ForkInfo
    fun setForkInfo(forkInfo: ForkInfo)
    fun migrateLegacyOpenGroup(legacyServerId: String, newServerId: String)
    fun getLastLegacySenderAddress(threadRecipientAddress: String): String?
    fun setLastLegacySenderAddress(threadRecipientAddress: String, senderRecipientAddress: String?)

}
