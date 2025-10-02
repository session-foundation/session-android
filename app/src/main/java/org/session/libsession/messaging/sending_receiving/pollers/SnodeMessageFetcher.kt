package org.session.libsession.messaging.sending_receiving.pollers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.session.libsession.snode.SnodeClock
import org.session.libsession.snode.StorageRPCService
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.snode.endpoint.Authenticated
import org.session.libsession.snode.endpoint.Retrieve
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.database.LokiAPIDatabase

class SnodeMessageFetcher @AssistedInject constructor(
    val lokiAPIDatabase: LokiAPIDatabase,
    private val storageRPCService: StorageRPCService,
    private val snodeClock: SnodeClock,
    val swarmAccountId: AccountId,
    @Assisted private val swarmAuthProvider: () -> SwarmAuth,
) {

    inner class FetchResult(
        private val newMessages: List<Retrieve.Message>,
        private val snode: Snode,
        private val namespace: Int
    ) {
        suspend fun <T> processMessagesInBatches(
            chunkSize: Int = 50,
            processor: suspend (List<Retrieve.Message>) -> T
        ): List<T> {
            return buildList {
                for (batch in newMessages.asSequence().chunked(chunkSize)) {
                    add(processor(batch))

                    // For each batch's completion, update the last processed message hash and
                    // store the processed message hashes to avoid reprocessing.
                    if (batch.isNotEmpty()) {
                        lokiAPIDatabase.setLastMessageHashValue(
                            snode = snode,
                            publicKey = swarmAccountId.hexString,
                            namespace = namespace,
                            newValue = batch.maxBy { it.timestamp }.hash
                        )

                        lokiAPIDatabase.addNewMessageHashes(
                            hashes = batch.asSequence().map { it.hash },
                            swarmPubKey = swarmAccountId.hexString,
                            namespace = namespace
                        )
                    }
                }
            }
        }

        suspend fun <T> processMessages(processor: suspend (List<Retrieve.Message>) -> T): T {
            return processMessagesInBatches(
                chunkSize = newMessages.size,
                processor = processor
            ).first()
        }
    }


    suspend fun fetchLatestMessages(
        snode: Snode,
        namespace: Int,
        maxSize: Int?,
    ): FetchResult {
        val request = Retrieve.Request(
            namespace = null,
            lastHash = lokiAPIDatabase.getLastMessageHashValue(
                snode = snode,
                publicKey = swarmAccountId.hexString,
                namespace = namespace
            ).orEmpty(),
            maxSize = maxSize
        )

        val messages = storageRPCService.call(
            snode = snode,
            endpoint = Authenticated(
                clock = snodeClock,
                realEndpoint = Retrieve,
                swarmAuth = swarmAuthProvider(),
            ),
            req = request,
        ).messages
            .sortedBy { it.timestamp }

        return FetchResult(
            newMessages = lokiAPIDatabase.dedupMessages(
                messages = messages,
                hash = Retrieve.Message::hash,
                swarmPubKey = swarmAccountId.hexString,
                namespace = namespace
            ).toList(),
            snode = snode,
            namespace = namespace
        )
    }


    @AssistedFactory
    interface Factory {
        fun create(
            swarmAccountId: AccountId,
            swarmAuthProvider: () -> SwarmAuth
        ): SnodeMessageFetcher
    }
}