package org.thoughtcrime.securesms.reviews

import android.content.Context
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.review.testing.FakeReviewManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import network.loki.messenger.BuildConfig
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class GooglePlayReviewsModule {
    @Provides
    @Singleton
    fun reviewManager(
        @ApplicationContext context: Context,
    ): ReviewManager {
        if (BuildConfig.DEBUG) {
            return FakeReviewManager(context)
        }

        return ReviewManagerFactory.create(context)
    }
}