package org.throughtcrime.securesms.reviews

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.thoughtcrime.securesms.reviews.StoreReviewManager

/**
 * Module for providing implementation of [StoreReviewManager].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StoreReviewManagerModule {
    @Binds
    abstract fun bindStoreReviewManager(
        storeReviewManager: NoOpStoreReviewManager
    ): StoreReviewManager
}