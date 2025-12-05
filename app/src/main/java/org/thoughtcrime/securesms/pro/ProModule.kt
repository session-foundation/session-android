package org.thoughtcrime.securesms.pro

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import network.loki.messenger.BuildConfig

@Module
@InstallIn(SingletonComponent::class)
class ProModule {
    @Provides
    fun provideProBackendConfig(): ProBackendConfig {
        return BuildConfig.PRO_BACKEND_DEV
    }
}