package org.session.libsession.messaging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.jobs.MessageSendJob
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.Device
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UsernameUtils
import org.thoughtcrime.securesms.pro.ProStatusManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessagingModuleConfiguration @Inject constructor(
    @param:ApplicationContext val context: Context,
    val storage: StorageProtocol,
    val device: Device,
    val messageDataProvider: MessageDataProvider,
    val configFactory: ConfigFactoryProtocol,
    val tokenFetcher: TokenFetcher,
    val groupManagerV2: GroupManagerV2,
    val clock: SnodeClock,
    val preferences: TextSecurePreferences,
    val deprecationManager: LegacyGroupDeprecationManager,
    val usernameUtils: UsernameUtils,
    val proStatusManager: ProStatusManager,
    val messageSendJobFactory: MessageSendJob.Factory,
) {

    companion object {
        @JvmStatic
        @Deprecated("Use properly DI components instead")
        val shared: MessagingModuleConfiguration
        get() = context.getSystemService(MESSAGING_MODULE_SERVICE) as MessagingModuleConfiguration

        const val MESSAGING_MODULE_SERVICE: String = "MessagingModuleConfiguration_MESSAGING_MODULE_SERVICE"

        private lateinit var context: Context

        @JvmStatic
        fun configure(context: Context) {
            this.context = context
        }
    }
}