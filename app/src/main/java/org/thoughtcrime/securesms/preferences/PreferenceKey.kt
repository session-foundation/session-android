package org.thoughtcrime.securesms.preferences

import kotlinx.serialization.KSerializer
import org.session.libsession.utilities.serializable.InstantAsMillisSerializer
import java.time.Instant

interface PreferenceKey<out T>{
    val name: String
    val strategy: Strategy<out T>

    data class Simple<T>(
        override val name: String,
        override val strategy: Strategy<out T>
    ): PreferenceKey<T>

    sealed interface Strategy<T> {
        data class PrimitiveInt(val defaultValue: Int) : Strategy<Int>
        data class PrimitiveLong(val defaultValue: Long) : Strategy<Long>
        data class PrimitiveFloat(val defaultValue: Float) : Strategy<Float>
        data class PrimitiveBoolean(val defaultValue: Boolean) : Strategy<Boolean>
        data class PrimitiveString(val defaultValue: String?) : Strategy<String?>
        data class Enum<T: kotlin.Enum<*>>(val choices: List<T>, val defaultValue: T?): Strategy<T?>
        data class Json<T>(val serializer: KSerializer<T>) : Strategy<T?>
    }

    companion object {
        fun boolean(name: String, defaultValue: Boolean = false): PreferenceKey<Boolean> =
            Simple(name, Strategy.PrimitiveBoolean(defaultValue))

        fun string(name: String, defaultValue: String? = null): PreferenceKey<String?> =
            Simple(name, Strategy.PrimitiveString(defaultValue))

        fun int(name: String, defaultValue: Int): PreferenceKey<Int> =
            Simple(name, Strategy.PrimitiveInt(defaultValue))

        fun long(name: String, defaultValue: Long): PreferenceKey<Long> =
            Simple(name, Strategy.PrimitiveLong(defaultValue))

        fun float(name: String, defaultValue: Float): PreferenceKey<Float> =
            Simple(name, Strategy.PrimitiveFloat(defaultValue))

        fun <T> json(name: String, serializer: KSerializer<T>): PreferenceKey<T?> =
            Simple(name, Strategy.Json(serializer))

        inline fun <reified T: Enum<*>> enum(name: String, defaultValue: T? = null): PreferenceKey<T?> {
            return Simple(name, Strategy.Enum(T::class.java.enumConstants!!.toList(), defaultValue))
        }

        fun instantAsMills(name: String): PreferenceKey<Instant?> =
            json(name, InstantAsMillisSerializer())
    }
}
