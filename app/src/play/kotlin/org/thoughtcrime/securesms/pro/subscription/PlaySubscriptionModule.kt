package org.thoughtcrime.securesms.pro.subscription

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaySubscriptionModule {

    @Binds
    @IntoSet
    abstract fun providePlayStoreManager(manager: PlayStoreSubscriptionManager): SubscriptionManager

}