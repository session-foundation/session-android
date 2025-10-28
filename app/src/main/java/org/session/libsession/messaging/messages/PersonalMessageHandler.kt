package org.session.libsession.messaging.messages

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import network.loki.messenger.libsession_util.Namespace
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigMessage
import org.session.libsession.utilities.UserConfigType
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.ReceivedMessageDatabase

class PersonalMessageHandler @AssistedInject constructor(
    @Assisted repositoryAddress: Address.Conversable,
    receivedMessageDatabase: ReceivedMessageDatabase,
    @Assisted scope: CoroutineScope,
    private val configFactory: ConfigFactoryProtocol,
) : SwarmMessageHandler(repositoryAddress, receivedMessageDatabase, scope) {
    override suspend fun handleConfigMessages(messages: List<ReceivedMessageDatabase.Message>) {
        val toMerge = buildMap {
            for (message in messages) {
                val userConfigType = namespaceToUserConfigType(message.id.namespace)
                if (userConfigType != null) {
                    val configMessage = ConfigMessage(
                        hash = message.message.hash,
                        data = message.message.dataDecoded,
                        timestamp = message.message.timestamp.toEpochMilli()
                    )

                    getOrPut(userConfigType) { mutableListOf() }.add(configMessage)
                }
            }
        }

        for ((configType, configMessages) in toMerge) {
            runCatching {
                configFactory.mergeUserConfigs(configType, configMessages)
                Log.d(logTag, "Merged ${configMessages.size} messages for $configType")
            }.onFailure { error ->
                Log.e(logTag, "Failed to merge messages for $configType", error)
            }
        }
    }

    override suspend fun handleRegularMessages(messages: List<ReceivedMessageDatabase.Message>) {
        TODO("Not yet implemented")
    }

    override val configNamespaces: List<Int> = UserConfigType.entries.map { it.namespace }
    override val regularMessageNamespace: Int = Namespace.DEFAULT()

    private fun namespaceToUserConfigType(namespace: Int): UserConfigType? {
        return UserConfigType.entries.firstOrNull { it.namespace == namespace }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            repositoryAddress: Address.Conversable,
            scope: CoroutineScope
        ): PersonalMessageHandler
    }
}