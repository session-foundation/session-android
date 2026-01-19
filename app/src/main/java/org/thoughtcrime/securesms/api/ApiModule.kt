package org.thoughtcrime.securesms.api

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import org.thoughtcrime.securesms.api.onion.OnionSessionApiExecutor
import org.thoughtcrime.securesms.api.snode.SnodeApiBatcher
import org.thoughtcrime.securesms.api.snode.SnodeApiExecutor
import org.thoughtcrime.securesms.api.snode.SnodeApiExecutorImpl
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutorImpl
import org.thoughtcrime.securesms.dependencies.ManagerScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class APIModuleBinding {
    @Binds
    abstract fun bindSessionAPIExecutor(executor: OnionSessionApiExecutor): SessionAPIExecutor

    @Binds
    abstract fun bindHttpAPIExecutor(executor: OkHttpApiExecutor): ApiExecutor<okhttp3.HttpUrl, okhttp3.Request, okhttp3.Response>

    @Binds
    abstract fun bindSwarmApiExecutor(executor: SwarmApiExecutorImpl): SwarmApiExecutor
}

@Module
@InstallIn(SingletonComponent::class)
class APIModule{
    @Provides
    @Singleton
    fun provideSnodeAPIExecutor(
        executor: SnodeApiExecutorImpl,
        batcher: SnodeApiBatcher,
        @ManagerScope scope: CoroutineScope,
        errorHandlingRPCExecutorFactory: ErrorHandlingApiExecutor.Factory
    ): SnodeApiExecutor {
        val batchExecutor = BatchApiExecutor(
            realExecutor = executor,
            batcher = batcher,
            scope = scope,
        )

        return errorHandlingRPCExecutorFactory.create(batchExecutor)
    }
}