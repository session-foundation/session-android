package org.thoughtcrime.securesms.preferences

import kotlinx.serialization.KSerializer

class PreferenceKey<out T> private constructor(
    val name: String,
    val strategy: Strategy<out T>,
) {

    sealed interface Strategy<T> {
        data class PrimitiveInt(val defaultValue: Int) : Strategy<Int>
        data class PrimitiveLong(val defaultValue: Long) : Strategy<Long>
        data class PrimitiveFloat(val defaultValue: Float) : Strategy<Float>
        data class PrimitiveBoolean(val defaultValue: Boolean) : Strategy<Boolean>
        data class PrimitiveString(val defaultValue: String?) : Strategy<String?>
        data class Json<T>(val serializer: KSerializer<T>) : Strategy<T?>
    }

    companion object {
        fun boolean(name: String, defaultValue: Boolean = false): PreferenceKey<Boolean> =
            PreferenceKey(name, Strategy.PrimitiveBoolean(defaultValue))
    }
}
