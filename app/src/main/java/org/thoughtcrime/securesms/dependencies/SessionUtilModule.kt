package org.thoughtcrime.securesms.dependencies

import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.groups.GroupScope
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.sending_receiving.pollers.LegacyClosedGroupPollerV2
import org.session.libsession.snode.SnodeClock
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UsernameUtils
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.thoughtcrime.securesms.database.SessionContactDatabase
import org.thoughtcrime.securesms.util.UsernameUtilsImpl
import javax.inject.Named
import javax.inject.Singleton

const val POLLER_SCOPE = "poller_coroutine_scope"

@Module
@InstallIn(SingletonComponent::class)
object SessionUtilModule {

    @OptIn(DelicateCoroutinesApi::class)
    @Provides
    @Named(POLLER_SCOPE)
    fun providePollerScope(): CoroutineScope = GlobalScope

    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides
    @Named(POLLER_SCOPE)
    fun provideExecutor(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Provides
    @Singleton
    fun provideSnodeClock() = SnodeClock()

    @Provides
    @Singleton
    fun provideGroupScope() = GroupScope()

    @Provides
    @Singleton
    fun provideLegacyGroupPoller(
        storage: StorageProtocol,
        deprecationManager: LegacyGroupDeprecationManager
    ): LegacyClosedGroupPollerV2 {
        return LegacyClosedGroupPollerV2(storage, deprecationManager)
    }


    @Provides
    @Singleton
    fun provideLegacyGroupDeprecationManager(prefs: TextSecurePreferences): LegacyGroupDeprecationManager {
        return LegacyGroupDeprecationManager(prefs)
    }

    @Provides
    @Singleton
    fun provideUsernameUtils(
        prefs: TextSecurePreferences,
        configFactory: ConfigFactory,
        sessionContactDatabase: SessionContactDatabase,
    ): UsernameUtils = UsernameUtilsImpl(
        prefs = prefs,
        configFactory = configFactory,
        sessionContactDatabase = sessionContactDatabase,
    )
}