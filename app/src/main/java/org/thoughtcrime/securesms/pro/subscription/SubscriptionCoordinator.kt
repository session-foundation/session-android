package org.thoughtcrime.securesms.pro.subscription

import jakarta.inject.Inject
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent

/**
 * Helper class to handle the selection and management of our available subscription providers
 */
class SubscriptionCoordinator @Inject constructor(
    private val availableManagers: Set<@JvmSuppressWildcards SubscriptionManager>,
    private val prefs: TextSecurePreferences
): OnAppStartupComponent {

    private var currentManager: SubscriptionManager? = null

    fun initializeSubscriptions() {
        val managers = availableManagers.toList()

        when {
            managers.isEmpty() -> {
                currentManager = NoOpSubscriptionManager()
            }
            managers.size == 1 -> {
                currentManager = managers.first()
            }
            else -> {
                val savedProviderId = prefs.getSubscriptionProvider()
                currentManager = managers.find { it.id == savedProviderId }
                // If null, user needs to choose
            }
        }

        Log.w("SubscriptionCoordinator", "Initialised subscription manager: $currentManager")
    }

    fun getAvailableProviders(): List<SubscriptionManager> = availableManagers.toList()

    fun switchProvider(providerId: String) {
        currentManager = availableManagers.find { it.id == providerId }
            ?: throw IllegalArgumentException("Provider not found: $providerId")

        prefs.setSubscriptionProvider(providerId)
    }

    fun getCurrentManager(): SubscriptionManager {
        return currentManager ?: throw IllegalStateException(
            "No subscription provider selected. Call initializeSubscriptions() first."
        )
    }

    fun needsProviderSelection(): Boolean {
        return availableManagers.size > 1 && currentManager == null
    }

    override fun onPostAppStarted() {
        initializeSubscriptions()
    }
}