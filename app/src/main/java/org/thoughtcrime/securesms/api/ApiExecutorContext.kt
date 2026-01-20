package org.thoughtcrime.securesms.api

import androidx.collection.arrayMapOf


class ApiExecutorContext {
    private var values: MutableMap<Any, Any>? = null

    private fun ensureInitialized(): MutableMap<Any, Any> {
        var current = values
        if (current == null) {
            current = arrayMapOf()
            values = current
        }
        return current
    }

    fun <T: Any> set(key: Key<T>, value: T) {
        ensureInitialized()[key] = value
    }

    fun remove(key: Key<*>) {
        values?.remove(key)
    }

    fun getRaw(key: Key<*>): Any? {
        return values?.get(key)
    }

    inline fun <reified T> get(key: Key<T>): T? {
        val raw = getRaw(key)
        if (raw != null) {
            return raw as T
        }

        return null
    }

    inline fun <reified T: Any> getOrPut(key: Key<T>, defaultValue: () -> T): T {
        val existing = get<T>(key)
        if (existing != null) {
            return existing
        }

        val newValue = defaultValue()
        set(key, newValue)
        return newValue
    }

    interface Key<DataType>
}