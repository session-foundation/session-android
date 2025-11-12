package org.thoughtcrime.securesms.auth

import android.content.Context
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import network.loki.messenger.libsession_util.util.Bytes
import org.session.libsession.snode.SnodeClock
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.crypto.KeyStoreHelper
import org.thoughtcrime.securesms.dependencies.ManagerScope
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing the login state of the user.
 * Persists the state securely using Android's Keystore system.
 */
@Singleton
class LoginStateRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
    @param:ManagerScope private val scope: CoroutineScope,
    private val snodeClock: Lazy<SnodeClock>,
) {
    private val sharedPrefs = context.getSharedPreferences("login_state", Context.MODE_PRIVATE)

    private val mutableLoggedInState: MutableStateFlow<LoggedInState?>


    init {
        var initialState = sharedPrefs.getString(PREF_KEY_STATE, null)?.let { serializedState ->
            runCatching {
                json.decodeFromString<LoggedInState>(
                    KeyStoreHelper.unseal(KeyStoreHelper.SealedData.fromString(serializedState)).toString(
                        Charsets.UTF_8)
                )

            }.onFailure {
                Log.e(TAG, "Unable to unseal login state", it)
            }.getOrNull()
        }

        if (initialState == null) {
            initialState = runCatching {
                // Can we load the state from the legacy format?
                IdentityKeyUtil.checkUpdate(context)
                val seed = IdentityKeyUtil.retrieve(context, "loki_seed")?.let(Hex::fromStringCondensed)?.let(::Bytes)

                if (seed != null) {
                    val existingNotificationKey = runCatching {
                        IdentityKeyUtil.retrieve(context, IdentityKeyUtil.NOTIFICATION_KEY)
                            ?.let(Hex::fromStringCondensed)?.let(::Bytes)
                    }.onFailure { e ->
                        Log.e(TAG, "Unable to retrieve legacy notification key. Regenerating", e)
                    }.getOrNull()

                    val generated = LoggedInState.generate(seed = seed.data)

                    if (existingNotificationKey != null) {
                        generated.copy(notificationKey = existingNotificationKey)
                    } else {
                        generated
                    }
                } else {
                    null
                }
            }.onFailure {
                Log.e(TAG, "Unable to load legacy login state", it)
            }.getOrNull()


            if (initialState != null) {
                // Migrate legacy state to new format
                Log.i(TAG, "Migrating legacy login state to new format")
                val sealedData = KeyStoreHelper.seal(json.encodeToString(initialState).toByteArray(Charsets.UTF_8))
                sharedPrefs.edit()
                    .putString(PREF_KEY_STATE, sealedData.serialize())
                    .apply()

                //TODO: Consider removing legacy data here after a grace period
            }
        }

        Log.d(TAG, "Loaded initial state: $initialState")

        mutableLoggedInState = MutableStateFlow(initialState)

        // Listen for changes to the login state and persist them
        scope.launch {
            mutableLoggedInState
                .drop(1) // Skip the initial value
                .collect { newState ->
                    if (newState != null) {
                        val sealedData = KeyStoreHelper.seal(json.encodeToString(newState).toByteArray(Charsets.UTF_8))
                        sharedPrefs.edit()
                            .putString(PREF_KEY_STATE, sealedData.serialize())
                            .apply()
                        Log.d(TAG, "Persisted new login state: $newState")
                    } else {
                        sharedPrefs.edit()
                            .remove(PREF_KEY_STATE)
                            .apply()
                        Log.d(TAG, "Cleared login state")
                    }
                }
        }
    }

    val loggedInState: StateFlow<LoggedInState?> get() = mutableLoggedInState

    fun requireLocalAccountId(): AccountId = requireLoggedInState().accountId

    /**
     * Returns the local number (account ID as hex string) of the logged-in user.
     * Throws an exception if no user is logged in.
     */
    fun requireLocalNumber(): String = requireLocalAccountId().hexString

    /**
     * Returns the local number (account ID as hex string) of the logged-in user,
     */
    fun getLocalNumber(): String? = loggedInState.value?.accountId?.hexString

    /**
     * Returns the current [LoggedInState] without observing for changes.
     */
    fun peekLoginState(): LoggedInState? = loggedInState.value

    fun requireLoggedInState(): LoggedInState = requireNotNull(loggedInState.value) {
        "No logged in user"
    }

    /**
     * A flow that starts emitting items from the provided [flowFactory] only when the user is logged in.
     * If the user logs out, the previous flow is cancelled and no items are emitted until the user logs in again.
     */
    fun <T> flowWithLoggedInState(flowFactory: () -> Flow<T>): Flow<T> {
        @Suppress("OPT_IN_USAGE")
        return loggedInState
            .map { it != null }
            .distinctUntilChanged()
            .flatMapLatest { loggedIn ->
                if (loggedIn) {
                    flowFactory()
                } else {
                    emptyFlow()
                }
            }
    }

    /**
     * Runs the provided [block] suspend function while the user is logged in, and cancels it
     * when logged out.
     */
    suspend fun runWhileLoggedIn(block: suspend () -> Unit) {
        loggedInState
            .map { it != null }
            .collectLatest { loggedIn ->
                if (loggedIn) {
                    block()
                }
            }
    }

    fun clear() {
        mutableLoggedInState.value = null
    }

    /**
     * Updates the current [LoggedInState] using the provided [updater] function.
     * The [org.thoughtcrime.securesms.auth.LoginStateRepository] will manage the persistence
     * of the data automatically. You don't need to do anything else.
     */
    fun update(updater: (LoggedInState?) -> LoggedInState) {
        mutableLoggedInState.update(updater)
    }


    /**
     * Ensures the user is logged in and valid rotating keypair exists, returning the pro master private key
     * and (validated or newly generated) pro state
     */
    fun ensureProMasterAndRotatingState(now: Instant = snodeClock.get().currentTime()): Pair<ByteArray, LoggedInState.ProState> {
        var masterKey: ByteArray? = null
        var proState: LoggedInState.ProState? = null

        update { oldState ->
            requireNotNull(oldState) {
                "User not logged in"
            }.ensureValidProRotatingKey(now).also {
                masterKey = it.proMasterPrivateKey
                proState = it.proState!!
            }
        }

        return masterKey!! to proState!!
    }

    companion object {
        private const val TAG = "LoginStateRepository"

        private const val PREF_KEY_STATE = "state"
    }
}