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
    private val databaseBackedComponents: Provider<DatabaseBackedStartupComponents>,
    @param:ManagerScope private val scope: CoroutineScope,
) {
    fun onPostAppStarted() {
        databaseMigrationManager.onPostAppStarted()

        // Everything in DatabaseBackedStartupComponents reaches the database, several of them from
        // their own constructors, and the open helper has nothing to hand out until the migration
        // has produced a usable secret. Resolving them before then means whichever touches it first
        // dies on a failure the user could otherwise have retried from, taking the process with it
        // and leaving the migration screen no chance to appear (#2213). A retry that succeeds still
        // reaches Completed, so this also starts them after a recovery.
        scope.launch(Dispatchers.Main) {
            databaseMigrationManager.migrationState.first {
                it == DatabaseMigrationManager.MigrationState.Completed
            }

            databaseBackedComponents.get().onPostAppStarted()
        }
    }
}

class DatabaseBackedStartupComponents private constructor(
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
        currentActivityObserver: CurrentActivityObserver,
        webRtcCallBridge: WebRtcCallBridge,
        persistentLogger: PersistentLogger,
        appDisguiseManager: AppDisguiseManager,
        tokenFetcher: TokenFetcher,
        emojiIndexLoader: EmojiIndexLoader,
        subscriptionCoordinator: SubscriptionCoordinator,
        authAwareHandler: AuthAwareComponentsHandler,
        snodeClock: SnodeClock,
        subscriptionManagers: Set<@JvmSuppressWildcards SubscriptionManager>,
        notificationChannelManager: NotificationChannelManager,
    ): this(
        components = listOf(
            groupPollerManager,
            expiredGroupManager,
            openGroupPollerManager,
            tokenManager,
            currentActivityObserver,
            webRtcCallBridge,
            persistentLogger,
            appDisguiseManager,
            tokenFetcher,
            emojiIndexLoader,
            subscriptionCoordinator,
            authAwareHandler,
            snodeClock,
            notificationChannelManager
        ) + subscriptionManagers
    )
}
