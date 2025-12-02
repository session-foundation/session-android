package org.thoughtcrime.securesms.auth

interface AuthAwareComponent {
    suspend fun doWhileLoggedIn(loggedInState: LoggedInState)

    fun onLoggedOut() {

    }
}
