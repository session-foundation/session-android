package org.thoughtcrime.securesms.rpc

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.rpc.onion.OnionRPCExecutor
import org.thoughtcrime.securesms.rpc.onion.OnionRPCExecutorImpl
import org.thoughtcrime.securesms.rpc.onion.SnodeOverOnionRPCExecutor
import org.thoughtcrime.securesms.rpc.storage.StorageSnodeRPCExecutor
import org.thoughtcrime.securesms.rpc.storage.StorageServerRPCExecutorImpl
import org.thoughtcrime.securesms.rpc.storage.StorageServerRequestBatcher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RPCModuleBinding {
    @Binds
    abstract fun bindSnodeRPCExecutor(executor: SnodeOverOnionRPCExecutor): SnodeRPCExecutor

    @Binds
    abstract fun bindOnionRPCExecutor(executor: OnionRPCExecutorImpl): OnionRPCExecutor

    @Binds
    abstract fun bindOkHttpRPCExecutor(executor: OkHttpRPCExecutor): RPCExecutor<okhttp3.HttpUrl, okhttp3.Request, okhttp3.Response>
}

@Module
@InstallIn(SingletonComponent::class)
class RPCModule{
    @Provides
    @Singleton
    fun provideStorageServerRPCExecutor(
        executor: StorageServerRPCExecutorImpl,
        batcher: StorageServerRequestBatcher,
        @ManagerScope scope: CoroutineScope,
        errorHandlingRPCExecutorFactory: ErrorHandlingRPCExecutor.Factory
    ): StorageSnodeRPCExecutor {
        val batchExecutor = BatchRPCExecutor(
            realExecutor = executor,
            batcher = batcher,
            scope = scope,
        )

        return errorHandlingRPCExecutorFactory.create(batchExecutor)
    }
}