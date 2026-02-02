package org.thoughtcrime.securesms.api

import androidx.collection.arrayMapOf

/**
 * A general-purpose context for passing data between API executor layers.
 *
 * To use, define a unique [Key] for data you want to store, and use [get], [set], and [getOrPut]
 * to manage values associated with that key. The context is normally created per API request,
 * allowing different layers of the request handling process to share information.
 *
 * Example usage: somewhere down in the middle of all the executor layers, a layer wants
 * to store the number of same exception's occurrence, so it can make decision on what to do for
 * next
 */
class ApiExecutorContext{
    private var values: MutableMap<Any, Any>? = null

    private fun ensureInitialized(): MutableMap<Any, Any> {
        var current = values
        if (current == null) {
            current = arrayMapOf()
            values = current
        }
        return current
    }

    fun <T: Any> set(key: Key<T>, value: T): ApiExecutorContext {
        ensureInitialized()[key] = value
        return this
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