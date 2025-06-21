package org.thoughtcrime.securesms.dependencies

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier
import org.session.libsession.utilities.AppTextSecurePreferences
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.groups.GroupManagerV2Impl
import org.thoughtcrime.securesms.notifications.OptimizedMessageNotifier
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.repository.DefaultConversationRepository
import org.thoughtcrime.securesms.sskenvironment.ProfileManager
import org.thoughtcrime.securesms.tokenpage.TokenRepository
import org.thoughtcrime.securesms.tokenpage.TokenRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class AppModule {
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindings {

    @Binds
    abstract fun bindTextSecurePreferences(preferences: AppTextSecurePreferences): TextSecurePreferences

    @Binds
    abstract fun bindConversationRepository(repository: DefaultConversationRepository): ConversationRepository

    @Binds
    abstract fun bindTokenRepository(repository: TokenRepositoryImpl): TokenRepository

    @Binds
    abstract fun bindGroupManager(groupManager: GroupManagerV2Impl): GroupManagerV2

    @Binds
    abstract fun bindProfileManager(profileManager: ProfileManager): SSKEnvironment.ProfileManagerProtocol

    @Binds
    abstract fun bindConfigFactory(configFactory: ConfigFactory): ConfigFactoryProtocol

    @Binds
    abstract fun bindMessageNotifier(notifier: OptimizedMessageNotifier): MessageNotifier

}

@Module
@InstallIn(SingletonComponent::class)
class ToasterModule {
    @Provides
    @Singleton
    fun provideToaster(@ApplicationContext context: Context) = (context as org.thoughtcrime.securesms.ApplicationContext)
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppComponent {
    fun getPrefs(): TextSecurePreferences

}