package org.session.libsession.messaging.jobs

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.session.libsession.messaging.jobs.BatchMessageReceiveJob.BatchMessageReceiveJobAssistedFactory

@Module
@InstallIn(SingletonComponent::class)
object JobQueueModule {
    @Provides
    @Singleton
    fun provideJobQueue(
        batchMessageReceiveJobAssistedFactory: BatchMessageReceiveJobAssistedFactory
    ): JobQueue {
        return JobQueue(batchMessageReceiveJobAssistedFactory)
    }
}
