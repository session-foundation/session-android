package org.thoughtcrime.securesms.notifications

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Semaphore
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class PushNotificationModule {

    @Qualifier
    @Retention(AnnotationRetention.SOURCE)
    annotation class PushProcessingSemaphore

    @Provides
    @Singleton
    @PushProcessingSemaphore
    fun providePushProcessingSemaphore(): Semaphore {
        return Semaphore(1)
    }
}