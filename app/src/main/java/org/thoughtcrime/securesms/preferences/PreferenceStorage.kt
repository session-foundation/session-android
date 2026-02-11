package org.thoughtcrime.securesms.preferences

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface PreferenceStorage {
    operator fun <T> set(key: PreferenceKey<T>, value: T)
    fun remove(key: PreferenceKey<*>)
    operator fun <T> get(key: PreferenceKey<T>): T

    fun changes(): Flow<PreferenceKey<*>>

    fun <T> watch(scope: CoroutineScope, key: PreferenceKey<T>): StateFlow<T>
}
