package org.thoughtcrime.securesms.dependencies

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.session.libsession.database.MessageDataProvider
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.BlindedIdMappingDatabase
import org.thoughtcrime.securesms.database.ConfigDatabase
import org.thoughtcrime.securesms.database.DraftDatabase
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
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.PushDatabase
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.database.SearchDatabase
import org.thoughtcrime.securesms.database.SessionContactDatabase
import org.thoughtcrime.securesms.database.SessionJobDatabase
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DatabaseComponent {

    companion object {
        @JvmStatic
        @Deprecated("Use Hilt to inject your dependencies instead")
        fun get(context: Context) = ApplicationContext.getInstance(context).databaseComponent
    }

    fun openHelper(): SQLCipherOpenHelper

    fun smsDatabase(): SmsDatabase
    fun mmsDatabase(): MmsDatabase
    fun attachmentDatabase(): AttachmentDatabase
    fun mediaDatabase(): MediaDatabase
    fun threadDatabase(): ThreadDatabase
    fun mmsSmsDatabase(): MmsSmsDatabase
    fun draftDatabase(): DraftDatabase
    fun pushDatabase(): PushDatabase
    fun groupDatabase(): GroupDatabase
    fun recipientDatabase(): RecipientDatabase
    fun groupReceiptDatabase(): GroupReceiptDatabase
    fun searchDatabase(): SearchDatabase
    fun lokiAPIDatabase(): LokiAPIDatabase
    fun lokiMessageDatabase(): LokiMessageDatabase
    fun lokiThreadDatabase(): LokiThreadDatabase
    fun lokiUserDatabase(): LokiUserDatabase
    fun lokiBackupFilesDatabase(): LokiBackupFilesDatabase
    fun sessionJobDatabase(): SessionJobDatabase
    fun sessionContactDatabase(): SessionContactDatabase
    fun reactionDatabase(): ReactionDatabase
    fun storage(): Storage
    fun attachmentProvider(): MessageDataProvider
    fun blindedIdMappingDatabase(): BlindedIdMappingDatabase
    fun groupMemberDatabase(): GroupMemberDatabase
    fun expirationConfigurationDatabase(): ExpirationConfigurationDatabase
    fun configDatabase(): ConfigDatabase
}