package org.session.libsession.messaging.utilities

import android.os.Parcelable
import kotlinx.serialization.Serializable
import org.session.libsession.utilities.ParcelableUtil
import org.session.libsession.utilities.ParcelableUtil.marshall
import org.session.libsession.utilities.serializable.BytesAsCompactB64Serializer

@Serializable
class Data(
    private val strings: Map<String, String> = emptyMap(),
    private val stringArrays: Map<String, Array<String>> = emptyMap(),
    private val integers: Map<String, Int> = emptyMap(),
    private val integerArrays: Map<String, IntArray> = emptyMap(),
    private val longs: Map<String, Long> = emptyMap(),
    private val longArrays: Map<String, LongArray> = emptyMap(),
    private val floats: Map<String, Float> = emptyMap(),
    private val floatArrays: Map<String, FloatArray> = emptyMap(),
    private val doubles: Map<String, Double> = emptyMap(),
    private val doubleArrays: Map<String, DoubleArray> = emptyMap(),
    private val booleans: Map<String, Boolean> = emptyMap(),
    private val booleanArrays: Map<String, BooleanArray> = emptyMap(),
    private val byteArrays: Map<String, @Serializable(with = BytesAsCompactB64Serializer::class) ByteArray> = emptyMap()
) {
    fun hasString(key: String): Boolean {
        return strings.containsKey(key)
    }

    fun getString(key: String): String? {
        throwIfAbsent(strings, key)
        return strings.get(key)
    }

    fun getStringOrDefault(key: String, defaultValue: String?): String? {
        if (hasString(key)) return getString(key)
        else return defaultValue
    }


    fun hasStringArray(key: String): Boolean {
        return stringArrays.containsKey(key)
    }

    fun getStringArray(key: String): Array<String>? {
        throwIfAbsent(stringArrays, key)
        return stringArrays.get(key)
    }


    fun hasInt(key: String): Boolean {
        return integers.containsKey(key)
    }

    fun getInt(key: String): Int {
        throwIfAbsent(integers, key)
        return integers.get(key)!!
    }

    fun getIntOrDefault(key: String, defaultValue: Int): Int {
        if (hasInt(key)) return getInt(key)
        else return defaultValue
    }


    fun hasIntegerArray(key: String): Boolean {
        return integerArrays.containsKey(key)
    }

    fun getIntegerArray(key: String): IntArray? {
        throwIfAbsent(integerArrays, key)
        return integerArrays.get(key)
    }


    fun hasLong(key: String): Boolean {
        return longs.containsKey(key)
    }

    fun getLong(key: String): Long {
        throwIfAbsent(longs, key)
        return longs.get(key)!!
    }

    fun getLongOrDefault(key: String, defaultValue: Long): Long {
        if (hasLong(key)) return getLong(key)
        else return defaultValue
    }


    fun hasLongArray(key: String): Boolean {
        return longArrays.containsKey(key)
    }

    fun getLongArray(key: String): LongArray {
        throwIfAbsent(longArrays, key)
        return longArrays.get(key)!!
    }


    fun hasFloat(key: String): Boolean {
        return floats.containsKey(key)
    }

    fun getFloat(key: String): Float {
        throwIfAbsent(floats, key)
        return floats.get(key)!!
    }

    fun getFloatOrDefault(key: String, defaultValue: Float): Float {
        if (hasFloat(key)) return getFloat(key)
        else return defaultValue
    }


    fun hasFloatArray(key: String): Boolean {
        return floatArrays.containsKey(key)
    }

    fun getFloatArray(key: String): FloatArray {
        throwIfAbsent(floatArrays, key)
        return floatArrays.get(key)!!
    }


    fun hasDouble(key: String): Boolean {
        return doubles.containsKey(key)
    }

    fun getDouble(key: String): Double {
        throwIfAbsent(doubles, key)
        return doubles.get(key)!!
    }

    fun getDoubleOrDefault(key: String, defaultValue: Double): Double {
        if (hasDouble(key)) return getDouble(key)
        else return defaultValue
    }


    fun hasDoubleArray(key: String): Boolean {
        return doubleArrays.containsKey(key)
    }

    fun getDoubleArray(key: String): DoubleArray? {
        throwIfAbsent(doubleArrays, key)
        return doubleArrays.get(key)
    }


    fun hasBoolean(key: String): Boolean {
        return booleans.containsKey(key)
    }

    fun getBoolean(key: String): Boolean {
        throwIfAbsent(booleans, key)
        return booleans.get(key)!!
    }

    fun getBooleanOrDefault(key: String, defaultValue: Boolean): Boolean {
        if (hasBoolean(key)) return getBoolean(key)
        else return defaultValue
    }


    fun hasBooleanArray(key: String): Boolean {
        return booleanArrays.containsKey(key)
    }

    fun getBooleanArray(key: String): BooleanArray? {
        throwIfAbsent(booleanArrays, key)
        return booleanArrays.get(key)
    }


    fun hasByteArray(key: String): Boolean {
        return byteArrays.containsKey(key)
    }

    fun getByteArray(key: String): ByteArray? {
        throwIfAbsent(byteArrays, key)
        return byteArrays.get(key)
    }


    fun hasParcelable(key: String): Boolean {
        return byteArrays.containsKey(key)
    }

    fun <T : Parcelable?> getParcelable(key: String, creator: Parcelable.Creator<T?>): T? {
        throwIfAbsent(byteArrays, key)
        val bytes = byteArrays.get(key)
        return ParcelableUtil.unmarshall<T?>(bytes!!, creator)
    }


    private fun throwIfAbsent(map: Map<*, *>, key: String) {
        check(map.containsKey(key)) { "Tried to retrieve a value with key '" + key + "', but it wasn't present." }
    }


    class Builder {
        private val strings: MutableMap<String, String> = HashMap()
        private val stringArrays: MutableMap<String, Array<String>> = HashMap()
        private val integers: MutableMap<String, Int> = HashMap()
        private val integerArrays: MutableMap<String, IntArray> = HashMap()
        private val longs: MutableMap<String, Long> = HashMap()
        private val longArrays: MutableMap<String, LongArray> = HashMap()
        private val floats: MutableMap<String, Float> = HashMap()
        private val floatArrays: MutableMap<String, FloatArray> = HashMap()
        private val doubles: MutableMap<String, Double> = HashMap()
        private val doubleArrays: MutableMap<String, DoubleArray> = HashMap()
        private val booleans: MutableMap<String, Boolean> = HashMap()
        private val booleanArrays: MutableMap<String, BooleanArray> = HashMap()
        private val byteArrays: MutableMap<String, ByteArray> = HashMap()

        fun putString(key: String, value: String): Builder {
            strings.put(key, value)
            return this
        }

        fun putStringArray(key: String, value: Array<String>): Builder {
            stringArrays.put(key, value)
            return this
        }

        fun putInt(key: String, value: Int): Builder {
            integers.put(key, value)
            return this
        }

        fun putIntArray(key: String, value: IntArray): Builder {
            integerArrays.put(key, value)
            return this
        }

        fun putLong(key: String, value: Long): Builder {
            longs.put(key, value)
            return this
        }

        fun putLongArray(key: String, value: LongArray): Builder {
            longArrays.put(key, value)
            return this
        }

        fun putFloat(key: String, value: Float): Builder {
            floats.put(key, value)
            return this
        }

        fun putFloatArray(key: String, value: FloatArray): Builder {
            floatArrays.put(key, value)
            return this
        }

        fun putDouble(key: String, value: Double): Builder {
            doubles.put(key, value)
            return this
        }

        fun putDoubleArray(key: String, value: DoubleArray): Builder {
            doubleArrays.put(key, value)
            return this
        }

        fun putBoolean(key: String, value: Boolean): Builder {
            booleans.put(key, value)
            return this
        }

        fun putBooleanArray(key: String, value: BooleanArray): Builder {
            booleanArrays.put(key, value)
            return this
        }

        fun putByteArray(key: String, value: ByteArray): Builder {
            byteArrays.put(key, value)
            return this
        }

        fun putParcelable(key: String, value: Parcelable): Builder {
            val bytes = marshall(value)
            byteArrays.put(key, bytes)
            return this
        }

        fun build(): Data {
            return Data(
                strings,
                stringArrays,
                integers,
                integerArrays,
                longs,
                longArrays,
                floats,
                floatArrays,
                doubles,
                doubleArrays,
                booleans,
                booleanArrays,
                byteArrays
            )
        }
    }
}