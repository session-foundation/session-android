package org.session.libsession.network

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.session.libsession.network.onion.OnionTransport
import org.session.libsession.network.snode.SnodePathStorage
import org.session.libsession.network.snode.SnodePoolStorage
import org.session.libsession.network.snode.SwarmStorage
import org.thoughtcrime.securesms.database.SnodeDatabase

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {

    @Binds
    abstract fun providePathStorage(storage: SnodeDatabase): SnodePathStorage

    @Binds
    abstract fun provideSwarmStorage(storage: SnodeDatabase): SwarmStorage

    @Binds
    abstract fun provideSnodePoolStorage(storage: SnodeDatabase): SnodePoolStorage

    @Binds
    abstract fun provideOnionTransport(transport: HttpOnionTransport): OnionTransport

}