package org.thoughtcrime.securesms.util

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.session.libsignal.utilities.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An observer to record currently started activity in the app.
 */
@Singleton
class CurrentActivityObserver @Inject constructor(
    application: Application
) {
    private val _currentActivity = MutableStateFlow<Activity?>(null)

    val currentActivity: StateFlow<Activity?> get() = _currentActivity

    init {
        application.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                _currentActivity.value = activity
                Log.d("CurrentActivityObserver", "Current activity set to: ${activity.javaClass.simpleName}")
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                if (_currentActivity.value === activity) {
                    _currentActivity.value = null
                    Log.d("CurrentActivityObserver", "Current activity set to null")
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
