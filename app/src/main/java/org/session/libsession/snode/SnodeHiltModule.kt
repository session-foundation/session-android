package org.session.libsession.snode

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class SnodeHiltModule {
    @Provides
    @Singleton
    fun provideStorageRPCService(
        onionRPCService: OnionRoutedRPCService,
        factory: AutoBatchRPCService.Factory,
    ): StorageRPCService {
        return factory.create(onionRPCService)
    }
}