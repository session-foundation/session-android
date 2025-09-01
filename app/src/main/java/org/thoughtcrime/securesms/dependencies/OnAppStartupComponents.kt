package org.thoughtcrime.securesms.dependencies

import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsession.messaging.sending_receiving.pollers.OpenGroupPollerManager
import org.session.libsession.messaging.sending_receiving.pollers.PollerManager
import org.session.libsession.snode.SnodeClock
import org.thoughtcrime.securesms.attachments.AvatarUploadManager
import org.thoughtcrime.securesms.configs.ConfigToDatabaseSync
import org.thoughtcrime.securesms.configs.ConfigUploader
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.disguise.AppDisguiseManager
import org.thoughtcrime.securesms.emoji.EmojiIndexLoader
import org.thoughtcrime.securesms.groups.ExpiredGroupManager
import org.thoughtcrime.securesms.groups.GroupPollerManager
import org.thoughtcrime.securesms.groups.handler.AdminStateSync
import org.thoughtcrime.securesms.groups.handler.CleanupInvitationHandler
import org.thoughtcrime.securesms.groups.handler.DestroyedGroupSync
import org.thoughtcrime.securesms.groups.handler.RemoveGroupMemberHandler
import org.thoughtcrime.securesms.logging.PersistentLogger
import org.thoughtcrime.securesms.migration.DatabaseMigrationManager
import org.thoughtcrime.securesms.notifications.BackgroundPollManager
import org.thoughtcrime.securesms.notifications.PushRegistrationHandler
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.pro.subscription.SubscriptionCoordinator
import org.thoughtcrime.securesms.service.ExpiringMessageManager
import org.thoughtcrime.securesms.tokenpage.TokenDataManager
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.CurrentActivityObserver
import org.thoughtcrime.securesms.util.VersionDataFetcher
import org.thoughtcrime.securesms.webrtc.CallMessageProcessor
import org.thoughtcrime.securesms.webrtc.WebRtcCallBridge
import javax.inject.Inject

class OnAppStartupComponents private constructor(
    private val components: List<OnAppStartupComponent>
) {
    fun onPostAppStarted() {
        components.forEach { it.onPostAppStarted() }
    }

    @Inject constructor(
        configUploader: ConfigUploader,
        snodeClock: SnodeClock,
        backgroundPollManager: BackgroundPollManager,
        appVisibilityManager: AppVisibilityManager,
        groupPollerManager: GroupPollerManager,
        expiredGroupManager: ExpiredGroupManager,
        openGroupPollerManager: OpenGroupPollerManager,
        databaseMigrationManager: DatabaseMigrationManager,
        tokenManager: TokenDataManager,
        expiringMessageManager: ExpiringMessageManager,
        currentActivityObserver: CurrentActivityObserver,
        webRtcCallBridge: WebRtcCallBridge,
        cleanupInvitationHandler: CleanupInvitationHandler,
        pollerManager: PollerManager,
        proStatusManager: ProStatusManager,
        persistentLogger: PersistentLogger,
        appDisguiseManager: AppDisguiseManager,
        removeGroupMemberHandler: RemoveGroupMemberHandler,
        destroyedGroupSync: DestroyedGroupSync,
        adminStateSync: AdminStateSync,
        callMessageProcessor: CallMessageProcessor,
        pushRegistrationHandler: PushRegistrationHandler,
        tokenFetcher: TokenFetcher,
        versionDataFetcher: VersionDataFetcher,
        threadDatabase: ThreadDatabase,
        emojiIndexLoader: EmojiIndexLoader,
        subscriptionCoordinator: SubscriptionCoordinator,
        avatarUploadManager: AvatarUploadManager,
        configToDatabaseSync: ConfigToDatabaseSync,
    ): this(
        components = listOf(
            configUploader,
            snodeClock,
            backgroundPollManager,
            appVisibilityManager,
            groupPollerManager,
            expiredGroupManager,
            openGroupPollerManager,
            databaseMigrationManager,
            tokenManager,
            expiringMessageManager,
            currentActivityObserver,
            webRtcCallBridge,
            cleanupInvitationHandler,
            pollerManager,
            proStatusManager,
            persistentLogger,
            appDisguiseManager,
            removeGroupMemberHandler,
            destroyedGroupSync,
            adminStateSync,
            callMessageProcessor,
            pushRegistrationHandler,
            tokenFetcher,
            versionDataFetcher,
            threadDatabase,
            emojiIndexLoader,
            subscriptionCoordinator,
            avatarUploadManager,
            configToDatabaseSync,
        )
    )
}
