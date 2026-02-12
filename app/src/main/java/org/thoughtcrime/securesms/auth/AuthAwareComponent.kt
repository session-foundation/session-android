package org.thoughtcrime.securesms.auth

/**
 * A component that is aware of authentication state changes.
 *
 * `doWhileLoggedIn` is called when the user is logged in, and provides the current [LoggedInState],
 * when the user logs out, the suspended function is cancelled and `onLoggedOut` will be called.
 *
 * The component is very likely to be lazily initialized as well.
 */
interface AuthAwareComponent {
    suspend fun doWhileLoggedIn(loggedInState: LoggedInState)

    fun onLoggedOut() {

    }
}
