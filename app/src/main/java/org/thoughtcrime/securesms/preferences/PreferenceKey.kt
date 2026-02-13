package org.thoughtcrime.securesms.preferences

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * A key definition for a preference. It includes the name of the preference and the
 * strategy for how to read/write the value.
 *
 * To use this class, you should use the factory methods in the companion object, which will create
 * instances of [PreferenceKey] with the appropriate strategy for the type of preference you want to
 * store.
 */
class PreferenceKey<out T>(
    val name: String,
    val strategy: Strategy<out T>,
) {

    sealed interface Strategy<T> {
        class PrimitiveInt(val defaultValue: Int) : Strategy<Int>
        class PrimitiveLong(val defaultValue: Long) : Strategy<Long>
        class PrimitiveFloat(val defaultValue: Float) : Strategy<Float>
        class PrimitiveBoolean(val defaultValue: Boolean) : Strategy<Boolean>
        class PrimitiveString(val defaultValue: String?) : Strategy<String?>
        object Bytes : Strategy<ByteArray?>
        class Enum<T : kotlin.Enum<*>>(val choices: List<T>, val defaultValue: T?) : Strategy<T?>
        class Json<T>(val serializer: KSerializer<T>) : Strategy<T?>
    }

    companion object {
        fun boolean(name: String, defaultValue: Boolean = false): PreferenceKey<Boolean> =
            PreferenceKey(name, Strategy.PrimitiveBoolean(defaultValue))

        fun string(name: String, defaultValue: String? = null): PreferenceKey<String?> =
            PreferenceKey(name, Strategy.PrimitiveString(defaultValue))

        fun int(name: String, defaultValue: Int): PreferenceKey<Int> =
            PreferenceKey(name, Strategy.PrimitiveInt(defaultValue))

        fun long(name: String, defaultValue: Long): PreferenceKey<Long> =
            PreferenceKey(name, Strategy.PrimitiveLong(defaultValue))

        fun float(name: String, defaultValue: Float): PreferenceKey<Float> =
            PreferenceKey(name, Strategy.PrimitiveFloat(defaultValue))

        inline fun <reified T> json(name: String): PreferenceKey<T?> =
            PreferenceKey(name, Strategy.Json(serializer<T>()))

        fun bytes(name: String): PreferenceKey<ByteArray?> =
            PreferenceKey(name, Strategy.Bytes)

        inline fun <reified T : Enum<*>> enum(
            name: String,
            defaultValue: T? = null
        ): PreferenceKey<T?> {
            return PreferenceKey(
                name,
                Strategy.Enum(T::class.java.enumConstants!!.toList(), defaultValue)
            )
        }
    }
}
