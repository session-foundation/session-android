package org.thoughtcrime.securesms.auth

import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class AuthAwareComponentsHandler @Inject constructor(
    private val components: Lazy<AuthAwareComponents>,
    private val loginStateRepository: LoginStateRepository,
    @param:ManagerScope private val scope: CoroutineScope,
) : OnAppStartupComponent {
    override fun onPostAppStarted() {
        scope.launch {
            loginStateRepository
                .loggedInState
                .scan(Pair<LoggedInState?, LoggedInState?>(null, null)) { (_, oldState), newState ->
                    oldState to newState
                }
                .collectLatest { (oldState, newState) ->
                    if (newState != null) {
                        supervisorScope {
                            for (comp in components.get().components) {
                                launch {
                                    delay((Math.random() * 1000).toLong()) // Stagger startups to avoid jank

                                    var component: AuthAwareComponent? = null

                                    try {
                                        component = comp.get()
                                        Log.d(TAG, "Processing component: ${component.javaClass.simpleName}")
                                        component.doWhileLoggedIn(newState)
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e

                                        Log.e(TAG, "Error processing component: ${component?.javaClass?.simpleName}", e)
                                    }
                                }
                            }
                        }

                    } else if (oldState != null) {
                        components.get().components.forEach {
                            it.get().onLoggedOut()
                        }
                    }
                }
        }
    }

    companion object {
        private const val TAG = "AuthAwareComponentsHandler"
    }
}