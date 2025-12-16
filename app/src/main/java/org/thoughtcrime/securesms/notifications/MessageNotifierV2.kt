package org.thoughtcrime.securesms.notifications

import org.session.libsession.utilities.ConfigFactoryProtocol
import org.thoughtcrime.securesms.auth.AuthAwareComponent
import org.thoughtcrime.securesms.auth.LoggedInState
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageNotifierV2 @Inject constructor(
    private val configFactory: ConfigFactoryProtocol,
    private val threadDatabase: ThreadDatabase,
    private val conversationRepository: ConversationRepository,
    private val currentActivityObserver: CurrentActivityObserver,
) : AuthAwareComponent {
    override suspend fun doWhileLoggedIn(loggedInState: LoggedInState) {
        TODO("Not yet implemented")
    }
}