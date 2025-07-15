package org.thoughtcrime.securesms.database.model.content

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass


@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator(MessageContent.DISCRIMINATOR)
sealed interface MessageContent {
    companion object {
        const val DISCRIMINATOR = "type"

        fun serializersModule() = SerializersModule {
            polymorphic(MessageContent::class) {
                subclass(DisappearingMessageUpdate::class)
                defaultDeserializer {
                    UnknownMessageContent.serializer()
                }
            }
        }
    }
}