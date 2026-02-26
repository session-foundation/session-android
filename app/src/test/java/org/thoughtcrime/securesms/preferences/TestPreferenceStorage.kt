package org.thoughtcrime.securesms.preferences

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TestPreferenceStorage : PreferenceStorage {
    private val preferenceStates = mutableMapOf<String, MutableStateFlow<Any?>>()
    private val changes = MutableSharedFlow<PreferenceKey<*>>(extraBufferCapacity = 32)

    override fun <T> set(key: PreferenceKey<T>, value: T) {
        stateFor(key).value = value
        changes.tryEmit(key)
    }

    override fun remove(key: PreferenceKey<*>) {
        stateForUntyped(key).value = defaultValueFor(key)
        changes.tryEmit(key)
    }

    override fun <T> get(key: PreferenceKey<T>): T {
        return stateFor(key).value
    }

    override fun changes(): Flow<PreferenceKey<*>> = changes

    override fun <T> watch(scope: CoroutineScope, key: PreferenceKey<T>): StateFlow<T> {
        return stateFor(key)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stateFor(key: PreferenceKey<T>): MutableStateFlow<T> {
        return stateForUntyped(key) as MutableStateFlow<T>
    }

    private fun stateForUntyped(key: PreferenceKey<*>): MutableStateFlow<Any?> {
        return preferenceStates.getOrPut(key.name) {
            MutableStateFlow(defaultValueFor(key))
        }
    }

    private fun defaultValueFor(key: PreferenceKey<*>): Any? {
        return when (val strategy = key.strategy) {
            is PreferenceKey.Strategy.PrimitiveInt -> strategy.defaultValue
            is PreferenceKey.Strategy.PrimitiveLong -> strategy.defaultValue
            is PreferenceKey.Strategy.PrimitiveFloat -> strategy.defaultValue
            is PreferenceKey.Strategy.PrimitiveBoolean -> strategy.defaultValue
            is PreferenceKey.Strategy.PrimitiveString -> strategy.defaultValue
            is PreferenceKey.Strategy.Enum<*> -> strategy.defaultValue
            is PreferenceKey.Strategy.Json<*> -> null
            is PreferenceKey.Strategy.Bytes -> null
        }
    }
}
