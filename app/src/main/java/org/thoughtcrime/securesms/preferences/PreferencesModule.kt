package org.thoughtcrime.securesms.preferences

import android.app.Application
import androidx.preference.PreferenceManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class PreferencesModule {
    @Provides
    @Singleton
    fun providesPreferenceStorage(
        application: Application,
        factory: SharedPreferenceStorage.Factory
    ): PreferenceStorage {
        return factory.create(
            prefs = PreferenceManager.getDefaultSharedPreferences(application)
        )
    }
}