package org.session.libsession.network

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.session.libsession.network.onion.http.HttpOnionTransport
import org.session.libsession.network.snode.DbSnodePathStorage
import org.session.libsession.network.snode.DbSnodePoolStorage
import org.session.libsession.network.snode.DbSwarmStorage
import org.session.libsession.network.snode.SnodePathStorage
import org.session.libsession.network.snode.SnodePoolStorage
import org.session.libsession.network.snode.SwarmStorage

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {

    @Binds
    abstract fun providePathStorage(storage: DbSnodePathStorage): SnodePathStorage

    @Binds
    abstract fun provideSwarmStorage(storage: DbSwarmStorage): SwarmStorage

    @Binds
    abstract fun provideSnodePoolStorage(storage: DbSnodePoolStorage): SnodePoolStorage

    @Binds
    abstract fun provideOnionTransport(transport: HttpOnionTransport): SnodePathStorage

}