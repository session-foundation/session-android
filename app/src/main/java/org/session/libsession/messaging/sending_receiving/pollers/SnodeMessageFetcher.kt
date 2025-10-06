package org.session.libsession.messaging.sending_receiving.pollers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.session.libsession.snode.SnodeClock
import org.session.libsession.snode.StorageRPCService
import org.session.libsession.snode.SwarmAuth
import org.session.libsession.snode.endpoint.Authenticated
import org.session.libsession.snode.endpoint.Retrieve
import org.session.libsession.utilities.Address.Companion.toAddress
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.database.LokiAPIDatabase
import org.thoughtcrime.securesms.database.ReceivedMessageHashDatabase

class SnodeMessageFetcher @AssistedInject constructor(
    val lokiAPIDatabase: LokiAPIDatabase,
    private val storageRPCService: StorageRPCService,
    private val snodeClock: SnodeClock,
    private val receivedMessageHashDatabase: ReceivedMessageHashDatabase,
    @Assisted private val swarmAuthProvider: () -> SwarmAuth,
) {

    inner class FetchResult(
        private val allMessages: List<Retrieve.Message>,
        private val swarmAccountId: AccountId,
        private val newMessages: List<Retrieve.Message>,
        private val snode: Snode,
        private val namespace: Int
    ) {
        suspend fun <T> processMessagesInBatches(
            chunkSize: Int = 50,
            processor: suspend (List<Retrieve.Message>) -> T
        ): List<T> {
            if (newMessages.isEmpty()) {
                val result = processor(newMessages)

                if (allMessages.isNotEmpty()) {
                    lokiAPIDatabase.setLastMessageHashValue(
                        snode = snode,
                        publicKey = swarmAccountId.hexString,
                        namespace = namespace,
                        newValue = allMessages.maxBy { it.timestamp }.hash
                    )
                }

                return listOf(result)
            }

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

                        receivedMessageHashDatabase.addNewMessageHashes(
                            hashes = batch.asSequence().map { it.hash },
                            repositoryAddress = swarmAccountId.toAddress(),
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
        val swarmAuth = swarmAuthProvider()
        val swarmAccountId = swarmAuth.accountId

        val request = Retrieve.Request(
            namespace = namespace.takeIf { it != 0 },
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
                swarmAuth = swarmAuth,
            ),
            req = request,
        ).messages
            .sortedBy { it.timestamp }

        val newMessages = receivedMessageHashDatabase.dedupMessages(
            messages = messages,
            hash = Retrieve.Message::hash,
            repositoryAddress = swarmAccountId.toAddress(),
            namespace = namespace
        ).toList()

        return FetchResult(
            swarmAccountId = swarmAccountId,
            newMessages = newMessages,
            allMessages = messages,
            snode = snode,
            namespace = namespace
        )
    }


    @AssistedFactory
    interface Factory {
        fun create(
            swarmAuthProvider: () -> SwarmAuth
        ): SnodeMessageFetcher
    }
}