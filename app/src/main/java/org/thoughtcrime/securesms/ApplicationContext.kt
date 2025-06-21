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
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.squareup.phrase.Phrase
import dagger.Lazy
import dagger.hilt.EntryPoints
import dagger.hilt.android.HiltAndroidApp
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.Logger.initLogger
import nl.komponents.kovenant.android.startKovenant
import nl.komponents.kovenant.android.stopKovenant
import org.conscrypt.Conscrypt
import org.session.libsession.database.MessageDataProvider
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.MessagingModuleConfiguration.Companion.configure
import org.session.libsession.messaging.groups.GroupManagerV2
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsession.messaging.sending_receiving.notifications.MessageNotifier
import org.session.libsession.messaging.sending_receiving.pollers.OpenGroupPollerManager
import org.session.libsession.messaging.sending_receiving.pollers.PollerManager
import org.session.libsession.snode.SnodeClock
import org.session.libsession.snode.SnodeModule.Companion.configure
import org.session.libsession.utilities.Device
import org.session.libsession.utilities.Environment
import org.session.libsession.utilities.ProfilePictureUtilities.resubmitProfilePictureIfNeeded
import org.session.libsession.utilities.SSKEnvironment.Companion.configure
import org.session.libsession.utilities.SSKEnvironment.ProfileManagerProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.TextSecurePreferences.Companion.pushSuffix
import org.session.libsession.utilities.Toaster
import org.session.libsession.utilities.UsernameUtils
import org.session.libsession.utilities.WindowDebouncer
import org.session.libsignal.utilities.HTTP.isConnectedToNetwork
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.AppContext.configureKovenant
import org.thoughtcrime.securesms.components.TypingStatusSender
import org.thoughtcrime.securesms.configs.ConfigUploader
import org.thoughtcrime.securesms.database.EmojiSearchDatabase
import org.thoughtcrime.securesms.database.LokiAPIDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.model.EmojiSearchData
import org.thoughtcrime.securesms.debugmenu.DebugActivity
import org.thoughtcrime.securesms.dependencies.AppComponent
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import org.thoughtcrime.securesms.dependencies.DatabaseModule.init
import org.thoughtcrime.securesms.disguise.AppDisguiseManager
import org.thoughtcrime.securesms.emoji.EmojiSource.Companion.refresh
import org.thoughtcrime.securesms.groups.ExpiredGroupManager
import org.thoughtcrime.securesms.groups.GroupPollerManager
import org.thoughtcrime.securesms.groups.handler.AdminStateSync
import org.thoughtcrime.securesms.groups.handler.CleanupInvitationHandler
import org.thoughtcrime.securesms.groups.handler.DestroyedGroupSync
import org.thoughtcrime.securesms.groups.handler.RemoveGroupMemberHandler
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.logging.AndroidLogger
import org.thoughtcrime.securesms.logging.PersistentLogger
import org.thoughtcrime.securesms.logging.UncaughtExceptionLogger
import org.thoughtcrime.securesms.migration.DatabaseMigrationManager
import org.thoughtcrime.securesms.notifications.BackgroundPollManager
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.notifications.PushRegistrationHandler
import org.thoughtcrime.securesms.providers.BlobUtils
import org.thoughtcrime.securesms.service.ExpiringMessageManager
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.sskenvironment.ReadReceiptManager
import org.thoughtcrime.securesms.sskenvironment.TypingStatusRepository
import org.thoughtcrime.securesms.tokenpage.TokenDataManager
import org.thoughtcrime.securesms.util.AppVisibilityManager
import org.thoughtcrime.securesms.util.Broadcaster
import org.thoughtcrime.securesms.util.VersionDataFetcher
import org.thoughtcrime.securesms.webrtc.CallMessageProcessor
import org.thoughtcrime.securesms.webrtc.WebRtcCallBridge
import org.webrtc.PeerConnectionFactory
import org.webrtc.PeerConnectionFactory.InitializationOptions
import java.io.IOException
import java.security.Security
import java.util.Arrays
import java.util.Timer
import java.util.concurrent.Executors
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
class ApplicationContext : Application(), DefaultLifecycleObserver,
    Toaster, Configuration.Provider {
    var broadcaster: Broadcaster? = null
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
    lateinit var persistentLogger: PersistentLogger

    @Inject lateinit var workerFactory: Lazy<HiltWorkerFactory>
    @Inject lateinit var lokiAPIDatabase: Lazy<LokiAPIDatabase>
    @Inject lateinit var storage: Lazy<Storage>
    @Inject lateinit var device: Lazy<Device>
    @Inject lateinit var messageDataProvider: Lazy<MessageDataProvider>
    @Inject lateinit var textSecurePreferences: Lazy<TextSecurePreferences>
    @Inject lateinit var configFactory: Lazy<ConfigFactory>
    @Inject lateinit var versionDataFetcher: Lazy<VersionDataFetcher>
    @Inject lateinit var pushRegistrationHandler: Lazy<PushRegistrationHandler>
    @Inject lateinit var tokenFetcher: Lazy<TokenFetcher>
    @Inject lateinit var groupManagerV2: Lazy<GroupManagerV2>
    @Inject lateinit var profileManager: Lazy<ProfileManagerProtocol>
    @Inject lateinit var callMessageProcessor: Lazy<CallMessageProcessor>
    private var messagingModuleConfiguration: MessagingModuleConfiguration? = null

    @Inject lateinit var configUploader: Lazy<ConfigUploader>
    @Inject lateinit var adminStateSync: Lazy<AdminStateSync>
    @Inject lateinit var destroyedGroupSync: Lazy<DestroyedGroupSync>
    @Inject lateinit var removeGroupMemberHandler: Lazy<RemoveGroupMemberHandler> // Exists here only to start upon app starts
    @Inject lateinit var tokenDataManager: Lazy<TokenDataManager> // Exists here only to start upon app starts
    @Inject lateinit var snodeClock: Lazy<SnodeClock>
    @Inject lateinit var migrationManager: Lazy<DatabaseMigrationManager>
    @Inject lateinit var appDisguiseManager: Lazy<AppDisguiseManager>

    @get:Deprecated(message = "Use proper DI to inject this component")
    @Inject
    lateinit var expiringMessageManager: Lazy<ExpiringMessageManager>

    @get:Deprecated(message = "Use proper DI to inject this component")
    @Inject
    lateinit var typingStatusRepository: Lazy<TypingStatusRepository>

    @get:Deprecated(message = "Use proper DI to inject this component")
    @Inject
    lateinit var typingStatusSender: Lazy<TypingStatusSender>

    @get:Deprecated(message = "Use proper DI to inject this component")
    @Inject
    lateinit var readReceiptManager: Lazy<ReadReceiptManager>

    @Inject lateinit var messageNotifierLazy: Lazy<MessageNotifier>
    @Inject lateinit var apiDB: Lazy<LokiAPIDatabase>
    @Inject lateinit var emojiSearchDb: Lazy<EmojiSearchDatabase>
    @Inject lateinit var webRtcCallBridge: Lazy<WebRtcCallBridge>
    @Inject lateinit var legacyGroupDeprecationManager: Lazy<LegacyGroupDeprecationManager>
    @Inject lateinit var cleanupInvitationHandler: Lazy<CleanupInvitationHandler>
    @Inject lateinit var usernameUtils: Lazy<UsernameUtils>
    @Inject lateinit var pollerManager: Lazy<PollerManager>

    @Inject
    lateinit var backgroundPollManager: Lazy<BackgroundPollManager> // Exists here only to start upon app starts

    @Inject
    lateinit var appVisibilityManager: Lazy<AppVisibilityManager> // Exists here only to start upon app starts

    @Inject
    lateinit var groupPollerManager: Lazy<GroupPollerManager> // Exists here only to start upon app starts

    @Inject
    lateinit var expiredGroupManager: Lazy<ExpiredGroupManager> // Exists here only to start upon app starts

    @Inject
    lateinit var openGroupPollerManager: Lazy<OpenGroupPollerManager>

    @Volatile
    var isAppVisible: Boolean = false

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory.get())
            .build()

    override fun getSystemService(name: String): Any? {
        if (MessagingModuleConfiguration.MESSAGING_MODULE_SERVICE == name) {
            return messagingModuleConfiguration!!
        }
        return super.getSystemService(name)
    }

    @get:Deprecated(message = "Use proper DI to inject this component")
    val prefs: TextSecurePreferences
        get() = EntryPoints.get(
            applicationContext,
            AppComponent::class.java
        ).getPrefs()

    @get:Deprecated(message = "Use proper DI to inject this component")
    val databaseComponent: DatabaseComponent
        get() = EntryPoints.get(
            applicationContext,
            DatabaseComponent::class.java
        )

    @get:Deprecated(message = "Use proper DI to inject this component")
    val messageNotifier: MessageNotifier
        get() = messageNotifierLazy.get()

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

    override fun toast(
        @StringRes stringRes: Int,
        toastLength: Int,
        parameters: Map<String, String>
    ) {
        val builder = Phrase.from(this, stringRes)
        for ((key, value) in parameters) {
            builder.put(key, value)
        }
        Toast.makeText(this, builder.format(), toastLength).show()
    }

    override fun toast(message: CharSequence, toastLength: Int) {
        Toast.makeText(this, message, toastLength).show()
    }

    override fun onCreate() {
        pushSuffix = BuildConfig.PUSH_KEY_SUFFIX

        init(this)
        configure(this)
        super<Application>.onCreate()

        messagingModuleConfiguration = MessagingModuleConfiguration(
            context = this,
            storage = storage.get(),
            device = device.get(),
            messageDataProvider = messageDataProvider.get(),
            configFactory = configFactory.get(),
            toaster = this,
            tokenFetcher = tokenFetcher.get(),
            groupManagerV2 = groupManagerV2.get(),
            clock = snodeClock.get(),
            preferences = textSecurePreferences.get(),
            deprecationManager = legacyGroupDeprecationManager.get(),
            usernameUtils = usernameUtils.get()
        )

        startKovenant()
        initializeSecurityProvider()
        initializeLogging()
        initializeCrashHandling()
        NotificationChannels.create(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        configureKovenant()
        broadcaster = Broadcaster(this)
        val useTestNet = textSecurePreferences.get().getEnvironment() == Environment.TEST_NET
        configure(apiDB.get(), broadcaster!!, useTestNet)
        configure(
            typingStatusRepository.get(), readReceiptManager.get(), profileManager.get(),
            messageNotifier, expiringMessageManager.get()
        )
        initializeWebRtc()
        initializeBlobProvider()
        resubmitProfilePictureIfNeeded()
        loadEmojiSearchIndexIfNeeded()
        refresh()

        val networkConstraint = NetworkConstraint.Factory(this).create()
        isConnectedToNetwork = { networkConstraint.isMet }

        snodeClock.get().start()
        pushRegistrationHandler.get().run()
        configUploader.get().start()
        destroyedGroupSync.get().start()
        adminStateSync.get().start()
        cleanupInvitationHandler.get().start()
        tokenDataManager.get().getTokenDataWhenLoggedIn()

        // Start our migration process as early as possible so we can show the user a progress UI
        migrationManager.get().requestMigration(fromRetry = false)

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
        lokiAPIDatabase.get()
        storage.get()
        device.get()
        messageDataProvider.get()
        textSecurePreferences.get()
        configFactory.get()
        versionDataFetcher.get()
        pushRegistrationHandler.get()
        tokenFetcher.get()
        groupManagerV2.get()
        profileManager.get()
        callMessageProcessor.get()
        configUploader.get()
        adminStateSync.get()
        destroyedGroupSync.get()
        removeGroupMemberHandler.get()
        snodeClock.get()
        migrationManager.get()
        appDisguiseManager.get()
        expiringMessageManager.get()
        typingStatusRepository.get()
        typingStatusSender.get()
        readReceiptManager.get()
        messageNotifierLazy.get()
        apiDB.get()
        emojiSearchDb.get()
        webRtcCallBridge.get()
        pollerManager.get()
        legacyGroupDeprecationManager.get()
        cleanupInvitationHandler.get()
        usernameUtils.get()
        backgroundPollManager.get()
        appVisibilityManager.get()
        groupPollerManager.get()
        expiredGroupManager.get()
        openGroupPollerManager.get()
    }

    override fun onStart(owner: LifecycleOwner) {
        isAppVisible = true
        Log.i(TAG, "App is now visible.")
        KeyCachingService.onAppForegrounded(this)

        // If the user account hasn't been created or onboarding wasn't finished then don't start
        // the pollers
        if (textSecurePreferences.get().getLocalNumber() == null) {
            return
        }

        // fetch last version data
        versionDataFetcher.get().startTimedVersionCheck()
    }

    override fun onStop(owner: LifecycleOwner) {
        isAppVisible = false
        Log.i(TAG, "App is no longer visible.")
        KeyCachingService.onAppBackgrounded(this)
        messageNotifier.setVisibleThread(-1)
        versionDataFetcher.get().stopTimedVersionCheck()
    }

    override fun onTerminate() {
        stopKovenant() // Loki
        versionDataFetcher.get().stopTimedVersionCheck()
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
        persistentLogger = PersistentLogger(this)
        Log.initialize(AndroidLogger(), persistentLogger)
        initLogger()
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

    private fun loadEmojiSearchIndexIfNeeded() {
        Executors.newSingleThreadExecutor().execute {
            if (emojiSearchDb.get().query("face", 1).isEmpty()) {
                try {
                    assets.open("emoji/emoji_search_index.json").use { inputStream ->
                        val searchIndex = Arrays.asList(
                            *JsonUtil.fromJson(
                                inputStream,
                                Array<EmojiSearchData>::class.java
                            )
                        )
                        emojiSearchDb.get().setSearchIndex(searchIndex)
                    }
                } catch (e: IOException) {
                    Log.e(
                        "Loki",
                        "Failed to load emoji search index"
                    )
                }
            }
        }
    } // endregion

    companion object {
        const val PREFERENCES_NAME: String = "SecureSMS-Preferences"

        private val TAG: String = ApplicationContext::class.java.simpleName

        @JvmStatic
        fun getInstance(context: Context): ApplicationContext {
            return context.applicationContext as ApplicationContext
        }
    }
}
