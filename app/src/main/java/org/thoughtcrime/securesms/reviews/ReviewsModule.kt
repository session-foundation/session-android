package org.thoughtcrime.securesms.reviews

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ReviewsModule {
    @Binds
    abstract fun bindStoreReviewManager(
        storeReviewManager: NoOpStoreReviewManager
    ): StoreReviewManager
}