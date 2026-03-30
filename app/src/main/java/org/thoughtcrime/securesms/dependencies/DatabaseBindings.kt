package org.thoughtcrime.securesms.dependencies

import androidx.sqlite.db.SupportSQLiteOpenHelper
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.database.StorageProtocol
import org.session.libsession.utilities.MessageExpirationManagerProtocol
import org.session.libsignal.database.LokiAPIDatabaseProtocol
import org.thoughtcrime.securesms.attachments.DatabaseAttachmentProvider
import org.thoughtcrime.securesms.database.LokiAPIDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.service.ExpiringMessageManager

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseBindings {

    @Binds
    abstract fun bindStorageProtocol(storage: Storage): StorageProtocol

    @Binds
    abstract fun bindLokiAPIDatabaseProtocol(lokiAPIDatabase: LokiAPIDatabase): LokiAPIDatabaseProtocol

    @Binds
    abstract fun bindMessageExpirationManagerProtocol(manager: ExpiringMessageManager): MessageExpirationManagerProtocol

    @Binds
    abstract fun bindMessageProvider(provider: DatabaseAttachmentProvider): MessageDataProvider

    @Binds
    abstract fun bindSupportOpenHelper(openHelper: SQLCipherOpenHelper): SupportSQLiteOpenHelper
}