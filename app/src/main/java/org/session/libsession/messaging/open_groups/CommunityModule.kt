package org.session.libsession.messaging.open_groups

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import org.session.libsession.messaging.open_groups.api.CommunityApiBatcher
import org.session.libsession.messaging.open_groups.api.CommunityApiExecutor
import org.session.libsession.messaging.open_groups.api.CommunityApiExecutorImpl
import org.thoughtcrime.securesms.api.AutoRetryApiExecutor
import org.thoughtcrime.securesms.api.batch.BatchApiExecutor
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.dependencies.ManagerScope
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class CommunityModule {
    @Provides
    @Singleton
    fun provideCommunityApiExecutor(
        executor: CommunityApiExecutorImpl,
        batcher: CommunityApiBatcher,
        @ManagerScope scope: CoroutineScope,
    ): CommunityApiExecutor {
        return AutoRetryApiExecutor(
            actualExecutor = BatchApiExecutor(
                actualExecutor = executor,
                batcher = batcher,
                scope = scope
            )
        )
    }
}