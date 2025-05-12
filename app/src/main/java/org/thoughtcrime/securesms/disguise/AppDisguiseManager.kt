package org.thoughtcrime.securesms.disguise

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manage the app disguise feature, where you can observe the list of app aliases and selected alias.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AppDisguiseManager @Inject constructor(
    application: Application,
    private val prefs: TextSecurePreferences,
) {
    private val scope: CoroutineScope = GlobalScope

    private var currentActivity: Activity? = null

    val allAppAliases: Flow<List<AppAlias>> = flow {
        emit(
            application.packageManager
                .queryIntentActivities(
                    Intent(Intent.ACTION_MAIN)
                        .setPackage(application.packageName)
                        .addCategory(Intent.CATEGORY_LAUNCHER),
                    PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS
                )
                .asSequence()
                .filter {
                    it.activityInfo.targetActivity != null
                }
                .map { info ->
                    AppAlias(
                        activityAliasName = info.activityInfo.name,
                        defaultEnabled = info.activityInfo.enabled,
                        appName = info.activityInfo.labelRes.takeIf { it != 0 },
                        appIcon = info.activityInfo.icon.takeIf { it != 0 },
                    )
                }
                .toList()
        )
    }.flowOn(Dispatchers.Default)
        .shareIn(scope, started = SharingStarted.Lazily, replay = 1)

    private val prefChangeNotification = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * The currently selected app alias name. This doesn't equate to if the app disguise is on or off.
     */
    val selectedAppAliasName: StateFlow<String?> = prefChangeNotification
            .mapLatest { prefs.selectedActivityAliasName }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = prefs.selectedActivityAliasName
            )

    /**
     * Whether the app disguise is on or off.
     */
    val isOn: StateFlow<Boolean> = prefChangeNotification
            .mapLatest { prefs.isAppDiguiseOn }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = prefs.isAppDiguiseOn
            )

    init {
        scope.launch {
            combine(
                selectedAppAliasName,
                allAppAliases,
                isOn,
            ) { selected, all, on ->
                val enabledAlias = when {
                    on -> all.firstOrNull { it.activityAliasName == selected } ?: all.first { it.defaultEnabled }
                    else -> all.first { it.defaultEnabled }
                }

                all.map { alias ->
                    val state = if (alias === enabledAlias) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED

                    Triple(
                        ComponentName(application, alias.activityAliasName),
                        state,
                        alias.defaultEnabled
                    )
                }
            }.collectLatest { all ->
                val packageManager = application.packageManager
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    packageManager.setComponentEnabledSettings(
                        all.map { (name, state) ->
                            PackageManager.ComponentEnabledSetting(
                                name, state, PackageManager.DONT_KILL_APP or PackageManager.SYNCHRONOUS
                            )
                        }
                    )
                } else {
                    // Query current enable state for each component
                    val changed = all.filter { (name, desiredState, defaultEnabled) ->
                        val state = packageManager.getComponentEnabledSetting(name)
                        val wasEnabled = when (state) {
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                            else -> defaultEnabled
                        }

                        val willBeEnabled = (desiredState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) ||
                                (desiredState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && defaultEnabled)
                        wasEnabled != willBeEnabled
                    }

                    changed.forEach { (name, state) ->
                        packageManager.setComponentEnabledSetting(
                            name,
                            state,
                            PackageManager.DONT_KILL_APP
                        )
                    }

                    if (changed.isNotEmpty()) {
                        // Finish current activity if the disguise is on
                        currentActivity?.finishAffinity()
                    }
                }
            }
        }

        application.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                currentActivity = activity
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                if (currentActivity === activity) {
                    currentActivity = null
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun setSelectedAliasName(name: String?) {
        Log.d(TAG, "setSelectedAliasName: $name")
        prefs.selectedActivityAliasName = name
        prefChangeNotification.tryEmit(Unit)
    }

    fun setOn(on: Boolean) {
        prefs.isAppDiguiseOn = on
        prefChangeNotification.tryEmit(Unit)
    }

    data class AppAlias(
        val activityAliasName: String,
        val defaultEnabled: Boolean,
        @StringRes val appName: Int?,
        @DrawableRes val appIcon: Int?,
    )
}

private const val TAG = "AppDisguiseManager"