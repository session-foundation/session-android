package org.thoughtcrime.securesms.api

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Semaphore
import org.thoughtcrime.securesms.api.http.HTTP_EXECUTOR_SEMAPHORE_NAME
import org.thoughtcrime.securesms.api.http.HttpApiExecutor
import org.thoughtcrime.securesms.api.http.OkHttpApiExecutor
import org.thoughtcrime.securesms.api.http.createRegularNodeOkHttpClient
import org.thoughtcrime.securesms.api.http.createSeedSnodeOkHttpClient
import org.thoughtcrime.securesms.api.onion.OnionSessionApiExecutor
import org.thoughtcrime.securesms.api.onion.REGULAR_SNODE_HTTP_EXECUTOR_NAME
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.api.server.ServerApiExecutorImpl
import org.thoughtcrime.securesms.api.snode.SnodeApiBatcher
import org.thoughtcrime.securesms.api.snode.SnodeApiExecutor
import org.thoughtcrime.securesms.api.snode.SnodeApiExecutorImpl
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutor
import org.thoughtcrime.securesms.api.swarm.SwarmApiExecutorImpl
import org.thoughtcrime.securesms.dependencies.ManagerScope
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class APIModuleBinding {
    @Binds
    abstract fun bindSessionAPIExecutor(executor: OnionSessionApiExecutor): SessionApiExecutor

    @Binds
    abstract fun bindSwarmApiExecutor(executor: SwarmApiExecutorImpl): SwarmApiExecutor

    @Binds
    abstract fun bindServerApiExecutor(executor: ServerApiExecutorImpl) : ServerApiExecutor
}

@Module
@InstallIn(SingletonComponent::class)
class APIModule {
    @Provides
    @Singleton
    fun provideSnodeAPIExecutor(
        executor: SnodeApiExecutorImpl,
        batcher: SnodeApiBatcher,
        @ManagerScope scope: CoroutineScope,
    ): SnodeApiExecutor {
        return AutoRetryApiExecutor(
            actualExecutor = BatchApiExecutor(
                actualExecutor = executor,
                batcher = batcher,
                scope = scope,
            )
        )
    }

    @Provides
    @Singleton
    @Named(HTTP_EXECUTOR_SEMAPHORE_NAME)
    fun provideHttpExecutorSemaphore(): Semaphore {
        return Semaphore(20)
    }


    @Provides
    @Named(REGULAR_SNODE_HTTP_EXECUTOR_NAME)
    @Singleton
    fun provideRegularSnodeApiExecutor(
        @Named(HTTP_EXECUTOR_SEMAPHORE_NAME) semaphore: Semaphore,
    ): HttpApiExecutor {
        return OkHttpApiExecutor(
            client = createRegularNodeOkHttpClient().build(),
            semaphore = semaphore
        )
    }


    @Provides
    @Named(REGULAR_SNODE_HTTP_EXECUTOR_NAME)
    @Singleton
    fun provideSeedSnodeApiExecutor(
        @Named(HTTP_EXECUTOR_SEMAPHORE_NAME) semaphore: Semaphore,
    ): HttpApiExecutor {
        return OkHttpApiExecutor(
            client = createSeedSnodeOkHttpClient().build(),
            semaphore = semaphore
        )
    }
}