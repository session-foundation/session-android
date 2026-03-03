package org.session.libsession.messaging

import android.content.Context
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsession.network.SnodeClock
import org.session.libsession.network.onion.PathManager
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.Device
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.pro.ProStatusManager
import org.thoughtcrime.securesms.util.AvatarUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.java

@Singleton
class MessagingModuleConfiguration @Inject constructor(
    @param:ApplicationContext val context: Context,
    val storage: StorageProtocol,
    val device: Device,
    val messageDataProvider: MessageDataProvider,
    val configFactory: ConfigFactoryProtocol,
    val tokenFetcher: TokenFetcher,
    val groupManagerV2: GroupManagerV2,
    val preferences: TextSecurePreferences,
    val deprecationManager: LegacyGroupDeprecationManager,
    val recipientRepository: RecipientRepository,
    val avatarUtils: AvatarUtils,
    val proStatusManager: ProStatusManager,
    val json: Json,
    val snodeClock: SnodeClock,
    val pathManager: PathManager
) {

    companion object {
        const val MESSAGING_MODULE_SERVICE: String = "MessagingModuleConfiguration_MESSAGING_MODULE_SERVICE"

        private lateinit var context: Context

        @JvmStatic
        fun configure(context: Context) {
            this.context = context
        }

        /**
         * Works in BOTH:
         * - Production (ApplicationContext)
         * - Hilt tests (HiltTestApplication)
         */
        @JvmStatic
        val shared: MessagingModuleConfiguration
            get() {
                val appContext = context.applicationContext

                val entryPoint = EntryPointAccessors.fromApplication(
                    appContext,
                    MessagingModuleConfigurationEntryPoint::class.java
                )

                return entryPoint.messagingModuleConfiguration()
            }
    }
}