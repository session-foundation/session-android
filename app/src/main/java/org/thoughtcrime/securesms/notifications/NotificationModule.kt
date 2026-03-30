package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Semaphore
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class NotificationModule {

    @Qualifier
    @Retention(AnnotationRetention.SOURCE)
    annotation class PushProcessingSemaphore

    @Provides
    @Singleton
    @PushProcessingSemaphore
    fun providePushProcessingSemaphore(): Semaphore {
        return Semaphore(1)
    }

    @Provides
    @Singleton
    fun providesNotificationManagerCompat(@ApplicationContext context: Context): NotificationManagerCompat {
        return NotificationManagerCompat.from(context)
    }
}