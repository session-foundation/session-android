package org.thoughtcrime.securesms.dependencies

/**
 * An interface for components that need to be initialized, or get notified when the app starts.
 *
 * After implementing this interface, you need to add this component into [OnAppStartupComponents]
 */
interface OnAppStartupComponent {
    fun onPostAppStarted() {
    }
}
