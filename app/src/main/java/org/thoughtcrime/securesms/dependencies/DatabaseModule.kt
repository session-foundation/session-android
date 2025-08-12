package org.thoughtcrime.securesms.dependencies

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.session.libsession.database.MessageDataProvider
import org.thoughtcrime.securesms.attachments.DatabaseAttachmentProvider
import org.thoughtcrime.securesms.crypto.AttachmentSecret
import org.thoughtcrime.securesms.crypto.AttachmentSecretProvider
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.BlindedIdMappingDatabase
import org.thoughtcrime.securesms.database.ConfigDatabase
import org.thoughtcrime.securesms.database.DraftDatabase
import org.thoughtcrime.securesms.database.EmojiSearchDatabase
import org.thoughtcrime.securesms.database.ExpirationConfigurationDatabase
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.GroupMemberDatabase
import org.thoughtcrime.securesms.database.GroupReceiptDatabase
import org.thoughtcrime.securesms.database.LokiAPIDatabase
import org.thoughtcrime.securesms.database.LokiBackupFilesDatabase
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.LokiThreadDatabase
import org.thoughtcrime.securesms.database.LokiUserDatabase
import org.thoughtcrime.securesms.database.MediaDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.PushDatabase
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.database.SearchDatabase
import org.thoughtcrime.securesms.database.SessionContactDatabase
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.migration.DatabaseMigrationManager
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @JvmStatic
    fun init(context: Context) {
        System.loadLibrary("sqlcipher")
    }

    @Provides
    @Singleton
    fun provideAttachmentSecret(@ApplicationContext context: Context) = AttachmentSecretProvider.getInstance(context).orCreateAttachmentSecret

    @Provides
    @Singleton
    fun provideOpenHelper(manager: DatabaseMigrationManager): SQLCipherOpenHelper {
        return manager.openHelper
    }

    @Provides
    @Singleton
    fun provideSmsDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = SmsDatabase(context, openHelper)


    @Provides
    @Singleton
    fun provideAttachmentDatabase(@ApplicationContext context: Context,
                                  openHelper: Provider<SQLCipherOpenHelper>,
                                  attachmentSecret: AttachmentSecret) = AttachmentDatabase(context, openHelper, attachmentSecret)
    @Provides
    @Singleton
    fun provideMediaDatbase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = MediaDatabase(context, openHelper)

    @Provides
    @Singleton
    fun provideMmsSms(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = MmsSmsDatabase(context, openHelper)

    @Provides
    @Singleton
    fun provideDraftDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = DraftDatabase(context, openHelper)

    @Provides
    @Singleton
    fun providePushDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = PushDatabase(context,openHelper)

    @Provides
    @Singleton
    fun provideGroupDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = GroupDatabase(context,openHelper)

    @Provides
    @Singleton
    fun provideRecipientDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = RecipientDatabase(context,openHelper)

    @Provides
    @Singleton
    fun provideGroupReceiptDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = GroupReceiptDatabase(context,openHelper)

    @Provides
    @Singleton
    fun searchDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = SearchDatabase(context,openHelper)

    @Provides
    @Singleton
    fun provideLokiApiDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = LokiAPIDatabase(context,openHelper)

    @Provides
    @Singleton
    fun provideLokiMessageDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = LokiMessageDatabase(context,openHelper)

    @Provides
    @Singleton
    fun provideLokiThreadDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = LokiThreadDatabase(context,openHelper)

    @Provides
    @Singleton
    fun provideLokiUserDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = LokiUserDatabase(context,openHelper)

    @Provides
    @Singleton
    fun provideLokiBackupFilesDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = LokiBackupFilesDatabase(context,openHelper)


    @Provides
    @Singleton
    fun provideSessionContactDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = SessionContactDatabase(context,openHelper)

    @Provides
    @Singleton
    fun provideBlindedIdMappingDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = BlindedIdMappingDatabase(context, openHelper)

    @Provides
    @Singleton
    fun provideGroupMemberDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = GroupMemberDatabase(context, openHelper)

    @Provides
    @Singleton
    fun provideReactionDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = ReactionDatabase(context, openHelper)

    @Provides
    @Singleton
    fun provideEmojiSearchDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = EmojiSearchDatabase(context, openHelper)

    @Provides
    @Singleton
    fun provideExpirationConfigurationDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = ExpirationConfigurationDatabase(context, openHelper)

    @Provides
    @Singleton
    fun provideAttachmentProvider(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>): MessageDataProvider = DatabaseAttachmentProvider(context, openHelper)

    @Provides
    @Singleton
    fun provideConfigDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>): ConfigDatabase = ConfigDatabase(context, openHelper)

}