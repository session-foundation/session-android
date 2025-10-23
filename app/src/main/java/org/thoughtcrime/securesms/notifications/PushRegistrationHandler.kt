package org.thoughtcrime.securesms.notifications

import android.content.Context
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.session.libsession.messaging.notifications.TokenFetcher
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.UserConfigType
import org.session.libsession.utilities.userConfigsChanged
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.dependencies.ManagerScope
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent
import org.thoughtcrime.securesms.util.castAwayType
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A PN registration handler that watches for changes in the configs, and arrange the desired state
 * of push registrations into the database, and triggers [PushRegistrationWorker] to process them.
 */
@Singleton
class PushRegistrationHandler @Inject constructor(
    private val configFactory: ConfigFactory,
    private val preferences: TextSecurePreferences,
    private val tokenFetcher: TokenFetcher,
    @param:ApplicationContext private val context: Context,
    @param:ManagerScope private val scope: CoroutineScope,
) : OnAppStartupComponent {

    private var job: Job? = null

    @OptIn(FlowPreview::class)
    override fun onPostAppStarted() {
        require(job == null) { "Job is already running" }

        job = scope.launch {
            @Suppress("OPT_IN_USAGE")
            preferences.watchLocalNumber()
                .map { !it.isNullOrBlank() }
                .distinctUntilChanged()
                .flatMapLatest { isLoggedIn ->
                    if (isLoggedIn) {
                        combine(
                            configFactory.userConfigsChanged(
                                    onlyConfigTypes = EnumSet.of(UserConfigType.USER_GROUPS),
                                    debounceMills = 500
                                )
                                .castAwayType()
                                .onStart { emit(Unit) },
                            preferences.pushEnabled,
                            tokenFetcher.token.filterNotNull().filter { !it.isBlank() }
                        ) { _, _, _ -> Unit }
                    } else {
                        emptyFlow()
                    }
                }
                .collect {
                    PushRegistrationWorker.enqueue(context, delay = null).await()
                }
        }
    }


    companion object {
        private const val TAG = "PushRegistrationHandler"
    }
}
