package org.thoughtcrime.securesms.dependencies

import android.content.Context
import android.widget.Toast
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.utilities.AppTextSecurePreferences
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.Toaster
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.thoughtcrime.securesms.database.LokiAPIDatabase
import org.thoughtcrime.securesms.groups.GroupManagerV2Impl
import org.thoughtcrime.securesms.repository.ConversationRepository
import org.thoughtcrime.securesms.repository.DefaultConversationRepository
import org.thoughtcrime.securesms.sskenvironment.ProfileManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindTextSecurePreferences(preferences: AppTextSecurePreferences): TextSecurePreferences

    @Binds
    abstract fun bindConversationRepository(repository: DefaultConversationRepository): ConversationRepository

    @Binds
    abstract fun bindGroupManager(groupManager: GroupManagerV2Impl): GroupManagerV2

    @Binds
    abstract fun bindProfileManager(profileManager: ProfileManager): SSKEnvironment.ProfileManagerProtocol

    @Binds
    abstract fun bindConfigFactory(configFactory: ConfigFactory): ConfigFactoryProtocol

    @Binds
    abstract fun bindLokiAPIDatabaseProtocol(lokiAPIDatabase: LokiAPIDatabase): LokiAPIDatabaseProtocol
}

@Module
@InstallIn(SingletonComponent::class)
class ToasterModule {
    @Provides
    @Singleton
    fun provideToaster(@ApplicationContext context: Context) = Toaster { stringRes, toastLength, parameters ->
        val string = context.getString(stringRes, parameters)
        Toast.makeText(context, string, toastLength).show()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppComponent {
    fun getPrefs(): TextSecurePreferences

}