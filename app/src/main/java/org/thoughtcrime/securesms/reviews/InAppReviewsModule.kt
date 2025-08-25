package org.thoughtcrime.securesms.reviews

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass


/**
 * Module for providing JSON serializers.
 */
@Module
@InstallIn(SingletonComponent::class)
class ReviewsSerializerModule {
    @Provides
    @IntoSet
    fun provideReviewsSerializersModule(): SerializersModule = SerializersModule {
        polymorphic(InAppReviewState::class) {
            subclass(InAppReviewState.WaitingForTrigger::class)
            subclass(InAppReviewState.ShowingReviewRequest::class)
            subclass(InAppReviewState.DismissedForever::class)
            subclass(InAppReviewState.DismissedUntil::class)
        }
    }
}