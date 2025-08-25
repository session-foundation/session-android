package org.thoughtcrime.securesms.reviews

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Module for providing implementation of [StoreReviewManager].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StoreReviewManagerModule {
    @Binds
    abstract fun bindStoreReviewManager(
        storeReviewManager: PlayStoreReviewManager
    ): StoreReviewManager
}