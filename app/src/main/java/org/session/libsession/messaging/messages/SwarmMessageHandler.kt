package org.session.libsession.messaging.messages

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.session.libsession.utilities.Address
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.ReceivedMessageDatabase

abstract class SwarmMessageHandler(
    protected val repositoryAddress: Address.Conversable,
    protected val receivedMessageDatabase: ReceivedMessageDatabase,
    protected val scope: CoroutineScope,
) {
    protected val logTag: String = javaClass.name

    init {
        scope.launch {
            while (true) {
                val configMessages = receivedMessageDatabase.getUnprocessedMessagesSorted(
                    repositoryAddress = repositoryAddress,
                    namespaces = configNamespaces,
                    limit = 100
                )

                if (!configMessages.isEmpty()) {
                    handleConfigMessages(configMessages)
                    receivedMessageDatabase.markMessagesAsProcessed(configMessages.asSequence().map { it.id })
                    Log.d(logTag, "Handled ${configMessages.size} config messages")
                }

                val regularMessages = receivedMessageDatabase.getUnprocessedMessagesSorted(
                    repositoryAddress = repositoryAddress,
                    namespaces = listOf(regularMessageNamespace),
                    limit = 100
                )

                if (regularMessages.isNotEmpty()) {
                    handleRegularMessages(regularMessages)
                    receivedMessageDatabase.markMessagesAsProcessed(regularMessages.asSequence().map { it.id })
                    Log.d(logTag, "Handled ${regularMessages.size} regular messages")
                }

                if (configMessages.isEmpty() && regularMessages.isEmpty()) {
                    Log.d(logTag, "No more messages to handle, wait for db changes")
                    receivedMessageDatabase.changeNotification.first { it == repositoryAddress }
                }
            }
        }
    }

    protected abstract suspend fun handleConfigMessages(messages: List<ReceivedMessageDatabase.Message>)
    protected abstract suspend fun handleRegularMessages(messages: List<ReceivedMessageDatabase.Message>)

    protected abstract val configNamespaces: List<Int>
    protected abstract val regularMessageNamespace: Int

    companion object {
    }
}