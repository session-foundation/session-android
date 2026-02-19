package org.thoughtcrime.securesms.pro.subscription

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.preferences.ProPreferences
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.dependencies.OnAppStartupComponent

/**
 * Helper class to handle the selection and management of our available subscription providers
 */
@Singleton
class SubscriptionCoordinator @Inject constructor(
    private val availableManagers: Set<@JvmSuppressWildcards SubscriptionManager>,
    private val noopSubManager: NoOpSubscriptionManager,
    private val textSecurePreferences: TextSecurePreferences,
    private val preferenceStorage: PreferenceStorage
): OnAppStartupComponent {

    private var currentManager: SubscriptionManager? = null

    fun initializeSubscriptions() {
        val managers = availableManagers.toList()

        when {
            managers.isEmpty() -> {
                currentManager = noopSubManager
            }
            managers.size == 1 -> {
                currentManager = managers.first()
            }
            else -> {
                val savedProviderId = preferenceStorage[ProPreferences.SUBSCRIPTION_PROVIDER]
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

        preferenceStorage[ProPreferences.SUBSCRIPTION_PROVIDER] = providerId
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