package org.session.libsession.messaging.open_groups

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import org.session.libsession.messaging.open_groups.api.CommunityRequestBatcher
import org.thoughtcrime.securesms.api.AutoRetryApiExecutor
import org.thoughtcrime.securesms.api.BatchApiExecutor
import org.thoughtcrime.securesms.api.server.ServerApiExecutor
import org.thoughtcrime.securesms.dependencies.ManagerScope
import javax.inject.Named
import javax.inject.Singleton

const val COMMUNITY_API_EXECUTOR_NAME = "CommunityApiExecutor"

@Module
@InstallIn(SingletonComponent::class)
class CommunityModule {
    @Provides
    @Singleton
    @Named(COMMUNITY_API_EXECUTOR_NAME)
    fun provideCommunityApiExecutor(
        executor: ServerApiExecutor,
        batcher: CommunityRequestBatcher,
        @ManagerScope scope: CoroutineScope,
    ): ServerApiExecutor {
        return AutoRetryApiExecutor(
            actualExecutor = BatchApiExecutor(
                actualExecutor = executor,
                batcher = batcher,
                scope = scope
            )
        )
    }
}