package org.thoughtcrime.securesms.dependencies

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
import org.thoughtcrime.securesms.pro.subscription.SubscriptionCoordinator
import org.thoughtcrime.securesms.pro.subscription.SubscriptionManager
import org.thoughtcrime.securesms.tokenpage.TokenDataManager
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import org.thoughtcrime.securesms.webrtc.WebRtcCallBridge
import javax.inject.Inject

class OnAppStartupComponents private constructor(
    private val components: List<OnAppStartupComponent>
) {
    fun onPostAppStarted() {
        components.forEach { it.onPostAppStarted() }
    }

    @Inject constructor(
        groupPollerManager: GroupPollerManager,
        expiredGroupManager: ExpiredGroupManager,
        openGroupPollerManager: OpenGroupPollerManager,
        databaseMigrationManager: DatabaseMigrationManager,
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
    ): this(
        components = listOf(
            groupPollerManager,
            expiredGroupManager,
            openGroupPollerManager,
            databaseMigrationManager,
            tokenManager,
            currentActivityObserver,
            webRtcCallBridge,
            persistentLogger,
            appDisguiseManager,
            tokenFetcher,
            emojiIndexLoader,
            subscriptionCoordinator,
            authAwareHandler,
            snodeClock
        ) + subscriptionManagers
    )
}
