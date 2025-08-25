package org.thoughtcrime.securesms.database.model.content

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
class MessageContentModule {
    @Provides
    @IntoSet
    fun provideMessageContentSerializersModule(): SerializersModule {
        return SerializersModule {
            polymorphic(MessageContent::class) {
                subclass(DisappearingMessageUpdate::class)
                defaultDeserializer {
                    UnknownMessageContent.serializer()
                }
            }
        }
    }
}