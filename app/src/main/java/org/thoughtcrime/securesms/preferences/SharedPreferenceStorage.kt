package org.thoughtcrime.securesms.preferences

import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.util.mapToStateFlow

class SharedPreferenceStorage @AssistedInject constructor(
    @Assisted private val prefs: SharedPreferences,
    private val json: Json,
) : PreferenceStorage {
    private val changes = MutableSharedFlow<PreferenceKey<*>>()

    override fun <T> set(key: PreferenceKey<T>, value: T) {
        prefs.edit {
            when (val strategy = key.strategy) {
                is PreferenceKey.Strategy.PrimitiveBoolean -> putBoolean(key.name, value as Boolean)
                is PreferenceKey.Strategy.PrimitiveFloat -> putFloat(key.name, value as Float)
                is PreferenceKey.Strategy.PrimitiveInt -> putInt(key.name, value as Int)
                is PreferenceKey.Strategy.PrimitiveLong -> putLong(key.name, value as Long)
                is PreferenceKey.Strategy.PrimitiveString -> putString(key.name, value as? String)
                is PreferenceKey.Strategy.Json<*> -> putString(key.name,
                    value?.let {
                        @Suppress("UNCHECKED_CAST")
                        json.encodeToString(strategy.serializer as SerializationStrategy<Any?>, it)
                    }
                )
                is PreferenceKey.Strategy.Enum<*> -> {
                    putString(key.name, if (value == null) null else (value as Enum<*>).name)
                }
            }
        }

        changes.tryEmit(key)
    }

    override fun remove(key: PreferenceKey<*>) {
        prefs.edit {
            remove(key.name)
        }

        changes.tryEmit(key)
    }

    override fun <T> get(key: PreferenceKey<T>): T {
        @Suppress("UNCHECKED_CAST")
        return when (val strategy = key.strategy) {
            is PreferenceKey.Strategy.Json<*> -> prefs.getString(key.name, null)?.let { encoded ->
                runCatching {
                    json.decodeFromString(strategy.serializer, encoded)
                }.onFailure {
                    Log.e(TAG, "Unable to decode json for pref key = ${key.name}", it)
                }.getOrNull()
            }
            is PreferenceKey.Strategy.PrimitiveBoolean -> prefs.getBoolean(key.name, strategy.defaultValue)
            is PreferenceKey.Strategy.PrimitiveFloat -> prefs.getFloat(key.name, strategy.defaultValue)
            is PreferenceKey.Strategy.PrimitiveInt -> prefs.getInt(key.name, strategy.defaultValue)
            is PreferenceKey.Strategy.PrimitiveLong -> prefs.getLong(key.name, strategy.defaultValue)
            is PreferenceKey.Strategy.PrimitiveString -> prefs.getString(key.name, strategy.defaultValue)
            is PreferenceKey.Strategy.Enum<*> -> {
                val name = prefs.getString(key.name, null)
                if (name != null) {
                    strategy.choices.firstOrNull { it.name == name }.also {
                        if (it == null) {
                            Log.w(TAG, "Unable to find enum value for pref key = ${key.name}, name = $name")
                        }
                    }
                } else {
                    null
                }
            }
        } as T
    }

    override fun changes(): Flow<PreferenceKey<*>> {
        return changes
    }

    override fun <T> watch(
        scope: CoroutineScope,
        key: PreferenceKey<T>
    ): StateFlow<T> {
        return changes
            .filter { it.name == key.name }
            .mapToStateFlow(scope, initialData = key, valueGetter = { get(key) })
    }

    @AssistedFactory
    interface Factory {
        fun create(prefs: SharedPreferences): SharedPreferenceStorage
    }

    companion object {
        private const val TAG = "DefaultSharedPreferenceStorage"
    }
}