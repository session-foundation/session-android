package org.thoughtcrime.securesms.dependencies

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.thoughtcrime.securesms.util.RecipientChangeSource
import org.thoughtcrime.securesms.util.ContentObserverRecipientChangeSource

@Module
@InstallIn(SingletonComponent::class)
object ContentModule {

    @Provides
    fun providesContentResolver(@ApplicationContext context: Context) = context.contentResolver

    @Provides
    fun provideRecipientChangeSource(@ApplicationContext context: Context): RecipientChangeSource =
        ContentObserverRecipientChangeSource(context.contentResolver)

}