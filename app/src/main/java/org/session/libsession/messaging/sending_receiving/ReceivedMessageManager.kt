package org.session.libsession.messaging.sending_receiving

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.database.ReceivedMessageDatabase
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceivedMessageManager @Inject constructor(
    private val database: ReceivedMessageDatabase,
    private val handler: ReceivedMessageHandler,
    @param:ManagerScope private val scope: CoroutineScope,
): OnAppStartupComponent {
    override fun onPostAppStarted() {
        scope.launch {
//            database.getSwarmMessagesSorted()
        }
    }

    private fun launchMessageProcessing(
        address: Address,
        messages: ReceiveChannel<ReceivedMessageDatabase.Message>
    ) {
        scope.launch {
            for (msg in messages) {

            }
        }
    }
}