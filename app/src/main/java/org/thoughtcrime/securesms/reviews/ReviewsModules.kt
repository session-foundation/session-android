package org.thoughtcrime.securesms.reviews

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Module
@InstallIn(SingletonComponent::class)
abstract class ReviewsModule {
    @Binds
    abstract fun bindStoreReviewManager(
        storeReviewManager: NoOpStoreReviewManager
    ): StoreReviewManager
}

@Module
@InstallIn(SingletonComponent::class)
class ReviewsSerializerModule {
    @Provides
    @IntoSet
    fun provideReviewsSerializersModule(): SerializersModule = SerializersModule {
        // No specific serializers needed for reviews at the moment
        polymorphic(ReviewState::class) {
            subclass(ReviewState.WaitingForTrigger::class)
            subclass(ReviewState.ShowingReviewRequest::class)
            subclass(ReviewState.DismissedForever::class)
            subclass(ReviewState.DismissedUntil::class)
        }
    }
}