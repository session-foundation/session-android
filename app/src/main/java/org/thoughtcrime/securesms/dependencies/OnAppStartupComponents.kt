package org.thoughtcrime.securesms.dependencies

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsession.messaging.sending_receiving.pollers.OpenGroupPollerManager
import org.session.libsession.network.SnodeClock
import org.thoughtcrime.securesms.auth.AuthAwareComponentsHandler
import org.thoughtcrime.securesms.disguise.AppDisguiseManager
import org.thoughtcrime.securesms.emoji.EmojiIndexLoader
import org.thoughtcrime.securesms.groups.ExpiredGroupManager
import org.thoughtcrime.securesms.groups.GroupPollerManager
import org.thoughtcrime.securesms.logging.PersistentLogger
import org.thoughtcrime.securesms.migration.DatabaseMigrationManager
import org.thoughtcrime.securesms.notifications.NotificationChannelManager
import org.thoughtcrime.securesms.pro.subscription.SubscriptionCoordinator
import org.thoughtcrime.securesms.pro.subscription.SubscriptionManager
import org.thoughtcrime.securesms.tokenpage.TokenDataManager
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import org.thoughtcrime.securesms.webrtc.WebRtcCallBridge
import javax.inject.Inject
import javax.inject.Provider

class OnAppStartupComponents @Inject constructor(
    private val databaseMigrationManager: DatabaseMigrationManager,
    private val currentActivityObserver: CurrentActivityObserver,
    private val appDisguiseManager: AppDisguiseManager,
    private val notificationChannelManager: NotificationChannelManager,
    private val deferredComponents: Provider<DeferredStartupComponents>,
    @param:ManagerScope private val scope: CoroutineScope,
) {
    fun onPostAppStarted() {
        databaseMigrationManager.onPostAppStarted()

        // These three take no database dependency, and waiting costs something: CurrentActivityObserver
        // registers the activity lifecycle callbacks in its own constructor, so deferring it drops the
        // events that arrive before the migration finishes.
        currentActivityObserver.onPostAppStarted()
        appDisguiseManager.onPostAppStarted()
        notificationChannelManager.onPostAppStarted()

        // The rest wait because some of them reach the database, several from their own constructors,
        // and the open helper has nothing to hand out until the migration has produced a usable secret.
        // Whichever touches it first otherwise dies on a failure the user could have retried from,
        // taking the process with it and leaving the migration screen no chance to appear (#2213).
        // Deferring the whole remainder is the conservative choice: transitive reach through injected
        // dependencies is not cheap to establish per component, and starting one too early costs more
        // than starting one too late. A retry that succeeds still reaches Completed, so this also
        // starts them after a recovery.
        scope.launch(Dispatchers.Main) {
            databaseMigrationManager.migrationState.first {
                it == DatabaseMigrationManager.MigrationState.Completed
            }

            deferredComponents.get().onPostAppStarted()
        }
    }
}

class DeferredStartupComponents private constructor(
    private val components: List<OnAppStartupComponent>
) {
    fun onPostAppStarted() {
        components.forEach { it.onPostAppStarted() }
    }

    @Inject constructor(
        groupPollerManager: GroupPollerManager,
        expiredGroupManager: ExpiredGroupManager,
        openGroupPollerManager: OpenGroupPollerManager,
        tokenManager: TokenDataManager,
        webRtcCallBridge: WebRtcCallBridge,
        persistentLogger: PersistentLogger,
        tokenFetcher: TokenFetcher,
        emojiIndexLoader: EmojiIndexLoader,
        subscriptionCoordinator: SubscriptionCoordinator,
        authAwareHandler: AuthAwareComponentsHandler,
        snodeClock: SnodeClock,
        subscriptionManagers: Set<@JvmSuppressWildcards SubscriptionManager>,
    ): this(
        components = listOf(
            groupPollerManager,
            expiredGroupManager,
            openGroupPollerManager,
            tokenManager,
            webRtcCallBridge,
            persistentLogger,
            tokenFetcher,
            emojiIndexLoader,
            subscriptionCoordinator,
            authAwareHandler,
            snodeClock
        ) + subscriptionManagers
    )
}
