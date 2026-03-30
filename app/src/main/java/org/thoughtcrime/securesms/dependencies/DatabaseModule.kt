package org.thoughtcrime.securesms.dependencies

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.thoughtcrime.securesms.auth.LoginStateRepository
import org.thoughtcrime.securesms.crypto.AttachmentSecret
import org.thoughtcrime.securesms.crypto.AttachmentSecretProvider
import org.thoughtcrime.securesms.database.ConfigDatabase
import org.thoughtcrime.securesms.database.DraftDatabase
import org.thoughtcrime.securesms.database.EmojiSearchDatabase
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.GroupMemberDatabase
import org.thoughtcrime.securesms.database.LokiAPIDatabase
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.MediaDatabase
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.SearchDatabase
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
    fun provideAttachmentSecret(attachmentSecretProvider: AttachmentSecretProvider): AttachmentSecret {
        return attachmentSecretProvider.getOrCreateAttachmentSecret()
    }

    @Provides
    @Singleton
    fun provideOpenHelper(manager: DatabaseMigrationManager): SQLCipherOpenHelper {
        return manager.openHelper
    }

    @Provides
    @Singleton
    fun provideMediaDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>) = MediaDatabase(context, openHelper)


    @Provides
    @Singleton
    fun provideDraftDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>): DraftDatabase = DraftDatabase(context, openHelper)

    @Provides
    @Singleton
    fun provideGroupDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>, loginStateRepository: LoginStateRepository): GroupDatabase
        = GroupDatabase(context,openHelper, loginStateRepository)
    @Provides
    @Singleton
    fun searchDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>): SearchDatabase = SearchDatabase(context,openHelper)

    @Provides
    @Singleton
    fun provideLokiApiDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>): LokiAPIDatabase = LokiAPIDatabase(context,openHelper)

    @Provides
    @Singleton
    fun provideLokiMessageDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>): LokiMessageDatabase = LokiMessageDatabase(context,openHelper)

    @Provides
    @Singleton
    fun provideGroupMemberDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>): GroupMemberDatabase = GroupMemberDatabase(context, openHelper)

    @Provides
    @Singleton
    fun provideReactionDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>): ReactionDatabase = ReactionDatabase(context, openHelper)

    @Provides
    @Singleton
    fun provideEmojiSearchDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>): EmojiSearchDatabase = EmojiSearchDatabase(context, openHelper)

    @Provides
    @Singleton
    fun provideConfigDatabase(@ApplicationContext context: Context, openHelper: Provider<SQLCipherOpenHelper>): ConfigDatabase = ConfigDatabase(context, openHelper)

}