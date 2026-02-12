package org.thoughtcrime.securesms.preferences

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A simple key-value storage for preferences.
 */
interface PreferenceStorage {
    /**
     * Set the value for the given key. The type of the value must match the type of the key.
     */
    operator fun <T> set(key: PreferenceKey<T>, value: T)

    /**
     * Remove the value for the given key. After this call, the key will return its default value when accessed.
     */
    fun remove(key: PreferenceKey<*>)

    /**
     * Get the value for the given key. If the key does not have a value set, the default value will be returned.
     */
    operator fun <T> get(key: PreferenceKey<T>): T

    /**
     * Observe changes from the storage
     */
    fun changes(): Flow<PreferenceKey<*>>

    /**
     * Observe a given key for changes. The returned flow will emit the current value immediately
     * and then emit new values whenever the key changes.
     */

    fun <T> watch(scope: CoroutineScope, key: PreferenceKey<T>): StateFlow<T>
}
