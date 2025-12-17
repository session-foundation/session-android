package org.thoughtcrime.securesms.auth

import dagger.Lazy
import org.session.libsession.messaging.sending_receiving.pollers.PollerManager
import org.thoughtcrime.securesms.attachments.AvatarUploadManager
import org.thoughtcrime.securesms.configs.ConfigToDatabaseSync
import org.thoughtcrime.securesms.configs.ConfigUploader
import org.thoughtcrime.securesms.groups.handler.AdminStateSync
import org.thoughtcrime.securesms.groups.handler.CleanupInvitationHandler
import org.thoughtcrime.securesms.groups.handler.DestroyedGroupSync
import org.thoughtcrime.securesms.groups.handler.RemoveGroupMemberHandler
import org.thoughtcrime.securesms.notifications.BackgroundPollManager
import org.thoughtcrime.securesms.notifications.MarkReadProcessor
import org.thoughtcrime.securesms.notifications.MessageNotifierV2
import org.thoughtcrime.securesms.notifications.PushRegistrationHandler
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.service.ExpiringMessageManager
import org.thoughtcrime.securesms.webrtc.CallMessageProcessor
import javax.inject.Inject

/**
 * A collection of all [AuthAwareComponent]s in the application.
 *
 * This class is primarily used to inject all [AuthAwareComponent]s into a single location,
 * so that they can be started and stopped based on authentication state changes.
 */
class AuthAwareComponents(
    val components: List<Lazy<out AuthAwareComponent>>,
)  {

    @Inject
    constructor(
        expiringMessageManager: Lazy<ExpiringMessageManager>,
        adminStateSync: Lazy<AdminStateSync>,
        configUploader: Lazy<ConfigUploader>,
        avatarUploadManager: Lazy<AvatarUploadManager>,
        callMessageProcessor: Lazy<CallMessageProcessor>,
        cleanupInvitationHandler: Lazy<CleanupInvitationHandler>,
        removeGroupMemberHandler: Lazy<RemoveGroupMemberHandler>,
        destroyedGroupSync: Lazy<DestroyedGroupSync>,
        pushRegistrationHandler: Lazy<PushRegistrationHandler>,
        configToDatabaseSync: Lazy<ConfigToDatabaseSync>,
        proStatusManager: Lazy<ProStatusManager>,
        pollerManager: Lazy<PollerManager>,
        backgroundPollManager: Lazy<BackgroundPollManager>,
        markReadProcessor: Lazy<MarkReadProcessor>,
        messageNotifierV2: Lazy<MessageNotifierV2>,
    ): this(
        components = listOf<Lazy<out AuthAwareComponent>>(
            expiringMessageManager,
            adminStateSync,
            configUploader,
            avatarUploadManager,
            callMessageProcessor,
            cleanupInvitationHandler,
            removeGroupMemberHandler,
            destroyedGroupSync,
            pushRegistrationHandler,
            configToDatabaseSync,
            proStatusManager,
            pollerManager,
            backgroundPollManager,
            markReadProcessor,
            messageNotifierV2,
        )
    )
}