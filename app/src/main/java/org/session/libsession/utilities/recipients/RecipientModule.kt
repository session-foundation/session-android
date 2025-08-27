package org.session.libsession.utilities.recipients

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
class RecipientModule {
    @Provides
    @IntoSet
    fun provideProStatusSerializer(): SerializersModule = SerializersModule {
        polymorphic(ProStatus::class) {
            subclass(ProStatus.Pro::class)
            subclass(ProStatus.None::class)
        }
    }
}
