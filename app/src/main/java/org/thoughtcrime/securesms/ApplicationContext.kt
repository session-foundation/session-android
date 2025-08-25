/* Copyright (C) 2013 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.AsyncTask
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.Lazy
import dagger.hilt.EntryPoints
import dagger.hilt.android.HiltAndroidApp
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.LogLevel
import network.loki.messenger.libsession_util.util.Logger
import nl.komponents.kovenant.android.startKovenant
import nl.komponents.kovenant.android.stopKovenant
import org.conscrypt.Conscrypt
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.MessagingModuleConfiguration.Companion.configure
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier
import org.session.libsession.snode.SnodeModule
import org.session.libsession.utilities.ProfilePictureUtilities.resubmitProfilePictureIfNeeded
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.TextSecurePreferences.Companion.pushSuffix
import org.session.libsession.utilities.WindowDebouncer
import org.session.libsignal.utilities.HTTP.isConnectedToNetwork
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.AppContext.configureKovenant
import org.thoughtcrime.securesms.debugmenu.DebugActivity
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import org.thoughtcrime.securesms.dependencies.DatabaseModule.init
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponents
import org.thoughtcrime.securesms.emoji.EmojiSource.Companion.refresh
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.logging.AndroidLogger
import org.thoughtcrime.securesms.logging.PersistentLogger
import org.thoughtcrime.securesms.logging.UncaughtExceptionLogger
import org.thoughtcrime.securesms.migration.DatabaseMigrationManager
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.providers.BlobUtils
import org.thoughtcrime.securesms.service.KeyCachingService
import org.webrtc.PeerConnectionFactory
import org.webrtc.PeerConnectionFactory.InitializationOptions
import java.security.Security
import java.util.Timer
import javax.inject.Inject
import kotlin.concurrent.Volatile

/**
 * Will be called once when the TextSecure process is created.
 *
 *
 * We're using this as an insertion point to patch up the Android PRNG disaster,
 * to initialize the job manager, and to check for GCM registration freshness.
 *
 * @author Moxie Marlinspike
 */
@HiltAndroidApp
class ApplicationContext : Application(), DefaultLifecycleObserver, Configuration.Provider {
    var conversationListDebouncer: WindowDebouncer? = null
        get() {
            if (field == null) {
                field = WindowDebouncer(1000, Timer())
            }
            return field
        }
        private set
    private var conversationListHandlerThread: HandlerThread? = null
    private var conversationListHandler: Handler? = null

    @Inject lateinit var messagingModuleConfiguration: Lazy<MessagingModuleConfiguration>
    @Inject lateinit var workerFactory: Lazy<HiltWorkerFactory>
    @Inject lateinit var snodeModule: Lazy<SnodeModule>
    @Inject lateinit var sskEnvironment: Lazy<SSKEnvironment>

    @Inject lateinit var startupComponents: Lazy<OnAppStartupComponents>
    @Inject lateinit var persistentLogger: Lazy<PersistentLogger>
    @Inject lateinit var textSecurePreferences: Lazy<TextSecurePreferences>
    @Inject lateinit var migrationManager: Lazy<DatabaseMigrationManager>

    @Volatile
    var isAppVisible: Boolean = false

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory.get())
            .build()

    override fun getSystemService(name: String): Any? {
        if (MessagingModuleConfiguration.MESSAGING_MODULE_SERVICE == name) {
            return messagingModuleConfiguration.get()
        }

        return super.getSystemService(name)
    }

    @get:Deprecated(message = "Use proper DI to inject this component")
    val databaseComponent: DatabaseComponent
        get() = EntryPoints.get(
            applicationContext,
            DatabaseComponent::class.java
        )

    @get:Deprecated(message = "Use proper DI to inject this component")
    @Inject lateinit var messageNotifier: MessageNotifier

    val conversationListNotificationHandler: Handler
        get() {
            if (this.conversationListHandlerThread == null) {
                conversationListHandlerThread = HandlerThread("ConversationListHandler")
                conversationListHandlerThread!!.start()
            }
            if (this.conversationListHandler == null) {
                conversationListHandler =
                    Handler(conversationListHandlerThread!!.looper)
            }
            return conversationListHandler!!
        }


    override fun onCreate() {
        pushSuffix = BuildConfig.PUSH_KEY_SUFFIX

        init(this)
        configure(this)
        super<Application>.onCreate()

        startKovenant()
        initializeSecurityProvider()
        initializeLogging()
        initializeCrashHandling()
        NotificationChannels.create(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        configureKovenant()
        SnodeModule.sharedLazy = snodeModule
        SSKEnvironment.sharedLazy = sskEnvironment

        initializeWebRtc()
        initializeBlobProvider()
        resubmitProfilePictureIfNeeded()
        refresh()

        val networkConstraint = NetworkConstraint.Factory(this).create()
        isConnectedToNetwork = { networkConstraint.isMet }

        // add our shortcut debug menu if we are not in a release build
        if (BuildConfig.BUILD_TYPE != "release") {
            // add the config settings shortcut
            val intent = Intent(this, DebugActivity::class.java)
            intent.setAction(Intent.ACTION_VIEW)

            val shortcut = ShortcutInfoCompat.Builder(this, "shortcut_debug_menu")
                .setShortLabel("Debug Menu")
                .setLongLabel("Debug Menu")
                .setIcon(IconCompat.createWithResource(this, R.drawable.ic_settings))
                .setIntent(intent)
                .build()

            ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        }


        // Once we have done initialisation, access the lazy dependencies so we make sure
        // they are initialised.
        workerFactory.get()

        startupComponents.get()
            .onPostAppStarted()
    }

    override fun onStart(owner: LifecycleOwner) {
        isAppVisible = true
        Log.i(TAG, "App is now visible.")
        KeyCachingService.onAppForegrounded(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        isAppVisible = false
        Log.i(TAG, "App is no longer visible.")
        KeyCachingService.onAppBackgrounded(this)
        messageNotifier.setVisibleThread(-1)
    }

    override fun onTerminate() {
        stopKovenant() // Loki
        super.onTerminate()
    }


    // Loki
    private fun initializeSecurityProvider() {
        val conscryptPosition = Security.insertProviderAt(Conscrypt.newProvider(), 0)
        Log.i(TAG, "Installed Conscrypt provider: $conscryptPosition")

        if (conscryptPosition < 0) {
            Log.w(TAG, "Did not install Conscrypt provider. May already be present.")
        }
    }

    private fun initializeLogging() {
        Log.initialize(AndroidLogger(), persistentLogger.get())
        Logger.addLogger(object : Logger {
            private val tag = "LibSession"

            override fun log(message: String, category: String, level: LogLevel) {
                when (level) {
                    Logger.LOG_LEVEL_INFO -> Log.i(tag, "$category: $message")
                    Logger.LOG_LEVEL_DEBUG -> Log.d(tag, "$category: $message")
                    Logger.LOG_LEVEL_WARN -> Log.w(tag, "$category: $message")
                    Logger.LOG_LEVEL_ERROR -> Log.e(tag, "$category: $message")
                    Logger.LOG_LEVEL_CRITICAL -> Log.wtf(tag, "$category: $message")
                    Logger.LOG_LEVEL_OFF -> {}
                    else -> Log.v(tag, "$category: $message")
                }
            }
        })
    }

    private fun initializeCrashHandling() {
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(UncaughtExceptionLogger(originalHandler!!))
    }

    private fun initializeWebRtc() {
        try {
            PeerConnectionFactory.initialize(
                InitializationOptions.builder(this).createInitializationOptions()
            )
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, e)
        }
    }

    private fun initializeBlobProvider() {
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            BlobUtils.getInstance().onSessionStart(this)
        }
    }

    private fun resubmitProfilePictureIfNeeded() {
        resubmitProfilePictureIfNeeded(this)
    }

     // endregion

    companion object {
        const val PREFERENCES_NAME: String = "SecureSMS-Preferences"

        private val TAG: String = ApplicationContext::class.java.simpleName

        @JvmStatic
        fun getInstance(context: Context): ApplicationContext {
            return context.applicationContext as ApplicationContext
        }
    }
}
