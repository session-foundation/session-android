package org.session.libsession.messaging

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MessagingModuleConfigurationEntryPoint {
    fun messagingModuleConfiguration(): MessagingModuleConfiguration
}